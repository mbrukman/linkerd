package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Failure
import com.twitter.finagle.stats.{NullStatsReceiver => FNullStatsReceiver, StatsReceiver => FStatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Return, Throw}
import io.netty.handler.codec.http2._
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/**
 * Reads and writes a bi-directional HTTP/2 stream.
 *
 * Each stream transport has two "sides":
 *
 * - Dispatchers provide a stream with remote frames _from_ a socket
 *   into a `RemoteMsg`-typed message.  The `onRemoteMessage` future
 *   is satisfied when an initial HEADERS frame is received from the
 *   dispatcher.
 *
 * - Dispatchers write a `LocalMsg`-typed message _to_ a socket.  The
 *   stream transport reads from the message's stream until it
 *   _fails_, so that errors may be propagated if the local side of
 *   the stream is reset.
 *
 * When both sides of the stream are closed, the `onReset` future is
 * satisfied.
 *
 * Either side may reset the stream prematurely, causing the `onReset`
 * future to fail, typically with a [[StreamError]] indicating whether
 * the reset was initiated from the remote or local side of the
 * stream. This information is used by i.e. dispatchers to determine
 * whether a reset frame must be written.
 */
private[h2] trait Netty4StreamTransport[SendMsg <: Message, RecvMsg <: Message] {
  import Netty4StreamTransport._

  /** The HTTP/2 STREAM_ID of this stream. */
  def streamId: Int

  /** for logging */
  protected[this] def prefix: String

  protected[this] def transport: H2Transport.Writer

  protected[this] def statsReceiver: StatsReceiver

  protected[this] def mkRecvMsg(headers: Http2Headers, stream: Stream): RecvMsg

  /*
   * A stream's state is represented by the `StreamState` ADT,
   * reflecting the state diagram detailed in RFC7540 §5.1:
   *
   *                       +--------+
   *               recv ES |        | send ES
   *               ,-------|  open  |-------.
   *              /        |        |        \
   *             v         +--------+         v
   *     +----------+          |           +----------+
   *     |   half   |          |           |   half   |
   *     |  closed  |          | send R /  |  closed  |
   *     | (remote) |          | recv R    | (local)  |
   *     +----------+          |           +----------+
   *          |                |                 |
   *          | send ES /      |       recv ES / |
   *          | send R /       v        send R / |
   *          | recv R     +--------+   recv R   |
   *          `----------->|        |<-----------'
   *                       | closed |
   *                       |        |
   *                       +--------+
   *
   * (Note that SERVER_PUSH is not supported or represented in this
   * version of the state diagram).
   */

  private[this] sealed trait StreamState

  /**
   * The stream is open in both directions.
   *
   * When the StreamTransport is initialized (because a dispatcher has
   * a stream frame it needs to dispatch), it starts in the `Open`
   * state, because the stream exists and neither the remote nor local
   * halves of the stream have been closed (i.e. by sending a frame
   * with END_STREAM set).
   *
   * Since the local half of the stream is written from the dispatcher
   * to the transport, we simply track whether this has completed.
   *
   * The remote half of the connection is represented with a
   * [[RemoteState]] so that received frames may be passed inbound to
   * the application: first, by satisfying the `onRemoteMessage`
   * Future with [[RemotePending]], and then by offering data and
   * trailer frames to [[RemoteStreaming]].
   */
  private[this] case class Open(remote: RemoteState) extends StreamState with ResettableState {
    /**
     * Act on a stream reset by failing a pending or streaming remote.
     */
    override def reset(str: String, rst: Reset): Unit = {
      val str1 = s"$str -- Open.reset"
      log.debug("%s %s", str1, this)
      remote.reset(str1, rst)
    }
  }

  /**
   * The `SendMsg` has been entirely sent, and the `RecvMsg` is still
   * being received.
   */
  private[this] case class LocalClosed(remote: RemoteState)
    extends StreamState with ResettableState {
    override def reset(str: String, rst: Reset): Unit = {
      val str1 = s"$str -- LocalClosed.reset"
      log.debug("%s %s", str1, this)
      remote.reset(str1, rst)
    }
  }

  /**
   * The `RecvMsg` has been entirely received, and the `SendMsg` is still
   * being sent.
   *
   * Though the remote half is closed, it may reset the local half of
   * the stream.  This is achieved by failing the stream's underlying
   * queue so that the consumer of a stream fails `read()` with a
   * reset.
   */
  private[this] class RemoteClosed(q: AsyncQueue[Frame])
    extends StreamState with ResettableState {
    def close(str: String): Unit = {
      val str1 = s"$str -- RemoteClosed.close"
      log.debug("%s %s %s %d", str1, this, q.toString, q.size)
      q.fail(str1, Reset.NoError, discard = false)
    }
    override def reset(str: String, rst: Reset): Unit = {
      val str1 = s"$str -- RemoteClosed.reset"
      log.debug("%s %s %s %d", str1, this, q.toString, q.size)
      q.fail(str1, rst, discard = true)
    }
  }
  private[this] object RemoteClosed {
    def unapply(rc: RemoteClosed): Boolean = true
  }

  /** Both `RecvMsg` and `SendMsg` have been entirely sent. */
  private[this] case class Closed(error: Reset) extends StreamState

  /** The state of the remote side of a stream. */
  private[this] sealed trait RemoteState extends ResettableState

  /** A remote stream before the initial HEADERS frames have been received. */
  private[this] class RemotePending(p: Promise[RecvMsg]) extends RemoteState {
    def future: Future[RecvMsg] = {
      log.debug("[%s] RemotePending.future %s", prefix, this)
      p
    }
    def setMessage(str: String, rm: RecvMsg): Unit = {
      val str1 = s"$str -- RemotePending.setMessage"
      log.debug("%s %s", str1, this)
      p.setValue(rm)
    }
    override def reset(str: String, rst: Reset): Unit = {
      val str1 = s"$str -- RemotePending.reset"
      log.debug("%s %s", str1, this)
      p.setException(rst)
    }
  }
  private[this] object RemotePending {
    def unapply(rs: RemotePending): Boolean = true
  }

  /** A remote stream that has been initiated but not yet closed or reset. */
  private[this] class RemoteStreaming(q: AsyncQueue[Frame]) extends RemoteState {
    def toRemoteClosed(str: String): RemoteClosed = {
      val str1 = s"$str -- RemoteStreaming.toRemoteClosed"
      log.debug("%s %s %s %d", str1, this, q.toString, q.size)
      new RemoteClosed(q)
    }
    def offer(str: String, f: Frame): Boolean = {
      val str1 = s"$str -- RemoteStreaming.offer"
      log.debug("%s %s %s %d", str1, this, q.toString, q.size)
      q.offer(str1, f)
    }
    def close(str: String): Unit = {
      val str1 = s"$str -- RemoteStreaming.close"
      log.debug("%s %s %s %d", str1, this, q.toString, q.size)
      q.fail(str1, Reset.NoError, discard = false)
    }
    override def reset(str: String, rst: Reset): Unit = {
      val str1 = s"$str -- RemoteStreaming.reset"
      log.debug("%s %s %s %d", str1, this, q.toString, q.size)
      q.fail(str1, rst, discard = true)
    }
  }
  private[this] object RemoteStreaming {
    def apply(q: AsyncQueue[Frame]): RemoteStreaming = new RemoteStreaming(q)
    def unapply(rs: RemoteStreaming): Boolean = true
  }

  /** Helper to extract a RemoteState from a StreamState. */
  // WHY: why does this exist?
  private[this] object RemoteOpen {
    def unapply(s: StreamState): Option[RemoteState] = s match {
      case Open(r) => Some(r)
      case LocalClosed(r) => Some(r)
      case Closed(_) | RemoteClosed() => None
    }
  }

  /** Helper to match writable states. */
  private[this] object LocalOpen {
    def unapply(s: StreamState): Boolean = s match {
      case Open(_) | RemoteClosed() => true
      case Closed(_) | LocalClosed(_) => false
    }
  }

  /**
   * Because remote reads and local writes may occur concurrently,
   * this state is stored in the `stateRef` atomic reference. Writes
   * and reads are performed without locking (at the expense of
   * retrying on collision).
   */
  private[this] val stateRef: AtomicReference[StreamState] = {
    val str = s"[$prefix] remote message interrupted"
    val remoteMsgP = new Promise[RecvMsg]

    // When the remote message--especially a client's response--is
    // canceled, close the transport, sending a RST_STREAM as
    // appropriate.
    remoteMsgP.setInterruptHandler {
      case err: Reset =>
        val str1 = s"$str Reset"
        log.debug("%s %s", str1, err)
        localReset(str1, err)

      case Failure(Some(err: Reset)) =>
        val str1 = s"$str Failure"
        log.debug("%s %s", str1, err)
        localReset(str1, err)

      case f@Failure(_) if f.isFlagged(Failure.Interrupted) =>
        val str1 = s"$str Cancel"
        log.debug("%s %s", str1, f)
        localReset(str1, Reset.Cancel)

      case f@Failure(_) if f.isFlagged(Failure.Rejected) =>
        val str1 = s"$str Refused"
        log.debug("%s %s", str1, f)
        localReset(str1, Reset.Refused)

      case e =>
        val str1 = s"$str InternalError"
        log.debug("%s %s", str1, e)
        localReset(str1, Reset.InternalError)
    }

    new AtomicReference(Open(new RemotePending(remoteMsgP)))
  }

  val onRecvMessage: Future[RecvMsg] = stateRef.get match {
    case Open(rp@RemotePending()) => rp.future
    case s => sys.error(s"unexpected initialization state: $s")
  }

  /**
   * Satisfied successfully when the stream is fully closed with no
   * error.  An exception is raised with a Reset if the stream is
   * closed prematurely.
   */
  def onReset(str: String): Future[Unit] = {
    val str1 = s"$str -- onReset"
    resetP.onSuccess { _ =>
      log.debug("%s success", str1)
    }.onFailure { err =>
      log.debug("%s failure %s", str1, err)
    }
  }
  private[this] val resetP = new Promise[Unit]

  def isClosed = stateRef.get match {
    case Closed(_) => true
    case _ => false
  }

  def remoteReset(str: String, err: Reset): Unit = {
    val str1 = s"$str -- remoteReset"
    if (tryReset(str1, err)) err match {
      case err@Reset.NoError =>
        log.debug("%s setDone %s", str1, err)
        resetP.setDone(); ()
      case err =>
        log.debug("%s setException %s", str1, err)
        resetP.setException(StreamError.Remote(err))
    }
  }

  def localReset(str: String, err: Reset): Unit = {
    val str1 = s"$str -- localReset"
    if (tryReset(str1, err)) err match {
      case err@Reset.NoError =>
        log.debug("%s setDone %s", str1, err)
        resetP.setDone(); ()
      case err =>
        log.debug("%s setException %s", str1, err)
        resetP.setException(StreamError.Local(err))
    }
  }

  @tailrec private[this] def tryReset(str: String, err: Reset): Boolean = {
    val str1 = s"$str -- tryReset"
    stateRef.get match {
      case state: StreamState with ResettableState =>
        log.debug("%s ResettableState compareAndSet %s to Closed", str1, state)
        if (stateRef.compareAndSet(state, Closed(err))) {
          log.debug("%s ResettableState compareAndSet succeeded", str1)
          state.reset(str1, err)
          true
        } else {
          log.debug("%s ResettableState compareAndSet failed", str1)
          tryReset(str1, err)
        }

      case state =>
        log.debug("%s non-ResettableState %s", str1, state)
        false
    }
  }

  /**
   * Updates the stateRef to reflect that the local stream has been closed.
   *
   * If the ref is already local closed, then the remote stream is reset and
   * the reset promise results in an exception. If the ref is remote closed,
   * then the ref becomes fully closed and the reset promise is completed.
   */
  @tailrec private[this] def closeLocal(str: String): Unit =
    stateRef.get match {
      case Closed(_) =>
        val str1 = s"$str -- closeLocal Closed"
        log.debug(str1)

      case state@LocalClosed(remote) =>
        val str1 = s"$str -- closeLocal LocalClosed"
        log.debug(str1)
        log.debug("%s compareAndSet LocalClosed to Closed", str1)
        if (stateRef.compareAndSet(state, Closed(Reset.InternalError))) {
          log.debug("%s compareAndSet succeeded", str1)
          remote.reset(str1, Reset.InternalError)
          resetP.setException(new IllegalStateException("closing local from LocalClosed"))
        } else {
          log.debug("%s compareAndSet failed %s", str1, stateRef.get())
          closeLocal(str1)
        }

      case state@Open(remote) =>
        val str1 = s"$str -- closeLocal Open"
        log.debug(str1)
        log.debug("%s compareAndSet Open to LocalClosed", str1)
        if (!stateRef.compareAndSet(state, LocalClosed(remote))) {
          log.debug("%s compareAndSet failed %s", str1, stateRef.get())
          closeLocal(str1)
        } else {
          log.debug("%s compareAndSet succeeded", str1)
        }

      case state@RemoteClosed() =>
        val str1 = s"$str -- closeLocal RemoteClosed"
        log.debug(str1)
        log.debug("%s compareAndSet RemoteClosed to Closed", str1)
        if (stateRef.compareAndSet(state, Closed(Reset.NoError))) {
          log.debug("%s compareAndSet succeeded", str1)
          // state.close(str1)
          resetP.setDone(); ()
        } else {
          log.debug("%s compareAndSet failed %s", str1, stateRef.get())
          closeLocal(str1)
        }
    }

  /**
   * Offer a Netty Http2StreamFrame from the remote.
   *
   * `recv` returns false to indicate that a frame could not be
   * accepted.  This may occur, for example, when a message is
   * received on a closed stream.
   */
  @tailrec final def recv(in: Http2StreamFrame): Boolean = {
    val state = stateRef.get
    log.trace("[%s] admitting %s in %s", prefix, in.name, state)

    def resetFromRemote(str: String, remote: ResettableState, rst: Reset): Boolean = {
      val str1 = s"$str -- resetFromRemote"
      log.debug(str1)
      if (compareAndSet(str1, state, Closed(rst))) {
        log.debug("%s succeeded", str1)
        remote.reset(str1, rst)
        resetP.setException(StreamError.Remote(rst))
        true
      } else {
        log.debug("%s failed %s", str1, stateRef.get())
        false
      }
    }

    def resetFromLocal(str: String, remote: ResettableState, rst: Reset): Boolean = {
      val str1 = s"$str -- resetFromLocal"
      log.debug(str1)
      if (compareAndSet(str1, state, Closed(rst))) {
        log.debug("%s succeeded", str1)
        remote.reset(str1, rst)
        resetP.setException(StreamError.Local(rst))
        true
      } else {
        log.debug("%s failed %s", str1, stateRef.get())
        false
      }
    }

    def recvFrame(str: String, f: Frame, remote: RemoteStreaming): Boolean = {
      val str1 = s"$str -- recvFrame"
      log.debug(str1)
      if (remote.offer(str1, f)) {
        log.debug("%s succeeded", str1)
        statsReceiver.recordRemoteFrame(f)
        true
      } else {
        log.debug("%s failed %s", str1, stateRef.get())
        false
      }
    }

    def compareAndSet(str: String, expect: StreamState, update: StreamState): Boolean = {
      val str1 = s"$str -- compareAndSet"
      log.debug("%s %s to %s", str1, expect, update)
      if (stateRef.compareAndSet(expect, update)) {
        log.debug("%s succeeded", str1)
        true
      } else {
        log.debug("%s failed %s", str1, stateRef.get())
        false
      }
    }

    in match {
      case rst: Http2ResetFrame =>
        val err = Netty4Message.Reset.fromFrame(rst)
        state match {
          case Closed(_) =>
            val str = s"[$prefix] recv Http2ResetFrame Closed"
            log.debug(str)
            false

          case RemoteOpen(remote) =>
            val str = s"[$prefix] recv Http2ResetFrame RemoteOpen"
            log.debug(str)
            if (resetFromRemote(str, remote, err)) {
              statsReceiver.remoteResetCount.incr()
              true
            } else recv(rst)

          case state@RemoteClosed() =>
            val str = s"[$prefix] recv Http2ResetFrame RemoteClosed"
            log.debug(str)
            if (resetFromRemote(str, state, err)) {
              statsReceiver.remoteResetCount.incr()
              true
            } else recv(rst)
        }

      case hdrs: Http2HeadersFrame if hdrs.isEndStream =>
        state match {
          case Closed(_) =>
            val str = s"[$prefix] recv Http2HeadersFrame.isEndStream Closed"
            log.debug(str)
            false

          case state@RemoteClosed() =>
            val str = s"[$prefix] recv Http2HeadersFrame.isEndStream RemoteClosed"
            log.debug(str)
            if (resetFromLocal(str, state, Reset.InternalError)) true
            else recv(hdrs)

          // WHY not?
          //          case RemoteOpen(remote@RemotePending()) =>

          case Open(remote@RemotePending()) =>
            val str = s"[$prefix] recv Http2HeadersFrame.isEndStream Open/RemotePending"
            log.debug(str)
            log.debug("%s new AsyncQueue size 1", str)
            val q = new AsyncQueue[Frame](1) // WHY: is this 1?
            val msg = mkRecvMsg(hdrs.headers, Stream.empty(q))
            if (ConnectionHeaders.detect(msg.headers)) {
              if (resetFromLocal(str, remote, Reset.ProtocolError)) true
              else recv(hdrs)
            } else {
              if (compareAndSet(str, state, new RemoteClosed(q))) {
                remote.setMessage(str, msg)
                true
              } else recv(hdrs)
            }

          case Open(remote@RemoteStreaming()) =>
            val str = s"[$prefix] recv Http2HeadersFrame.isEndStream Open/RemoteStreaming"
            log.debug(str)
            if (compareAndSet(str, state, remote.toRemoteClosed(str))) {
              val f = toFrame(str, hdrs)
              // remote.offer(f)
              if (remote.offer(str, f)) {
                log.debug("%s remote.offer=true", str)
                statsReceiver.recordRemoteFrame(f)
                remote.close(str)
                // resetP.setDone()
                true
              } else {
                log.debug("%s remote.offer=false", str)
                false
              }
            } else recv(hdrs)

          case state@LocalClosed(remote@RemotePending()) =>
            val str = s"[$prefix] recv Http2HeadersFrame.isEndStream LocalClosed/RemotePending"
            log.debug(str)
            val msg = mkRecvMsg(hdrs.headers, NilStream)
            if (ConnectionHeaders.detect(msg.headers)) {
              if (resetFromLocal(str, state, Reset.ProtocolError)) true
              else recv(hdrs)
            } else {
              if (compareAndSet(str, state, Closed(Reset.NoError))) {
                remote.setMessage(str, msg)
                resetP.setDone()
                true
              } else recv(hdrs)
            }

          case LocalClosed(remote@RemoteStreaming()) => // SENDS TRAILER FRAME
            val str = s"[$prefix] recv Http2HeadersFrame.isEndStream LocalClosed/RemoteStreaming"
            log.debug(str)
            if (compareAndSet(str, state, Closed(Reset.NoError))) {
              val f = toFrame(str, hdrs)
              if (remote.offer(str, f)) {
                log.debug("%s remote.offer=true", str)
                statsReceiver.recordRemoteFrame(f)
                remote.close(str)
                resetP.setDone()
                true
              } else {
                log.debug("%s remote.offer=false", str)
                false
              }
            } else recv(hdrs)
        }

      case hdrs: Http2HeadersFrame =>
        // A HEADERS frame without END_STREAM may only be received to
        // initiate a message (i.e. when the remote is still pending).
        state match {
          case Closed(_) =>
            val str = s"[$prefix] recv Http2HeadersFrame Closed"
            log.debug(str)
            false

          case state@RemoteClosed() =>
            val str = s"[$prefix] recv Http2HeadersFrame RemoteClosed"
            log.debug(str)
            if (resetFromLocal(str, state, Reset.Closed)) false
            else recv(hdrs)

          case RemoteOpen(remote@RemoteStreaming()) =>
            val str = s"[$prefix] recv Http2HeadersFrame RemoteOpen/RemoteStreaming"
            log.debug(str)
            if (resetFromLocal(str, remote, Reset.InternalError)) false
            else recv(hdrs)

          case Open(remote@RemotePending()) =>
            val str = s"[$prefix] recv Http2HeadersFrame Open/RemotePending"
            log.debug(str)
            log.debug("%s new AsyncQueue size unlimited", str)
            val q = new AsyncQueue[Frame]
            val msg = mkRecvMsg(hdrs.headers, Stream(q))
            if (ConnectionHeaders.detect(msg.headers)) {
              if (resetFromLocal(str, remote, Reset.ProtocolError)) true
              else recv(hdrs)
            } else {
              if (compareAndSet(str, state, Open(RemoteStreaming(q)))) {
                remote.setMessage(str, msg)
                true
              } else recv(hdrs)
            }

          case LocalClosed(remote@RemotePending()) =>
            val str = s"[$prefix] recv Http2HeadersFrame LocalClosed/RemotePending"
            log.debug(str)
            log.debug("%s new AsyncQueue size unlimited", str)
            val q = new AsyncQueue[Frame]
            val msg = mkRecvMsg(hdrs.headers, Stream(q))
            if (ConnectionHeaders.detect(msg.headers)) {
              if (resetFromLocal(str, remote, Reset.ProtocolError)) true
              else recv(hdrs)
            } else {
              if (compareAndSet(str, state, LocalClosed(RemoteStreaming(q)))) {
                remote.setMessage(str, msg)
                true
              } else recv(hdrs)
            }
        }

      case data: Http2DataFrame if data.isEndStream =>
        state match {
          case Closed(_) =>
            val str = s"[$prefix] recv Http2DataFrame.isEndStream Closed"
            log.debug(str)
            false

          case state@RemoteClosed() =>
            val str = s"[$prefix] recv Http2DataFrame.isEndStream RemoteClosed"
            log.debug(str)
            if (resetFromLocal(str, state, Reset.Closed)) false
            else recv(data)

          case RemoteOpen(remote@RemotePending()) =>
            val str = s"[$prefix] recv Http2DataFrame.isEndStream RemoteOpen/RemotePending"
            log.debug(str)
            if (resetFromLocal(str, remote, Reset.InternalError)) false
            else recv(data)

          case Open(remote@RemoteStreaming()) =>
            val str = s"[$prefix] recv Http2DataFrame.isEndStream Open/RemoteStreaming"
            log.debug(str)
            if (compareAndSet(str, state, remote.toRemoteClosed(str))) {
              if (recvFrame(str, toFrame(str, data), remote)) true
              else throw new IllegalStateException("stream queue closed prematurely")
            } else recv(data)

          case LocalClosed(remote@RemoteStreaming()) =>
            val str = s"[$prefix] recv Http2DataFrame.isEndStream LocalClosed/RemoteStreaming"
            log.debug(str)
            if (compareAndSet(str, state, Closed(Reset.NoError))) {
              if (recvFrame(str, toFrame(str, data), remote)) {
                remote.close(str)
                resetP.setDone()
                true
              } else throw new IllegalStateException("stream queue closed prematurely")
            } else recv(data)
        }

      case data: Http2DataFrame =>
        state match {
          case Closed(_) =>
            val str = s"[$prefix] recv Http2DataFrame Closed"
            log.debug(str)
            false

          case state@RemoteClosed() =>
            val str = s"[$prefix] recv Http2DataFrame RemoteClosed"
            log.debug(str)
            if (resetFromLocal(str, state, Reset.Closed)) false
            else recv(data)

          case RemoteOpen(remote@RemotePending()) =>
            val str = s"[$prefix] recv Http2DataFrame RemoteOpen/RemotePending"
            log.debug(str)
            if (resetFromLocal(str, remote, Reset.InternalError)) false
            else recv(data)

          case Open(remote@RemoteStreaming()) =>
            val str = s"[$prefix] recv Http2DataFrame Open/RemoteStreaming"
            log.debug(str)
            if (recvFrame(str, toFrame(str, data), remote)) true
            else {
              if (resetFromLocal(str, remote, Reset.Closed)) false
              else recv(data)
            }

          case LocalClosed(remote@RemoteStreaming()) =>
            val str = s"[$prefix] recv Http2DataFrame LocalClosed/RemoteStreaming"
            log.debug(str)
            if (recvFrame(str, toFrame(str, data), remote)) true
            else {
              if (resetFromLocal(str, remote, Reset.Closed)) false
              else recv(data)
            }
        }
    }
  }

  private[this] def toFrame(str: String, f: Http2StreamFrame): Frame = {
    val str1 = s"$str -- toFrame"
    f match {
      case f: Http2DataFrame =>
        log.debug("%s Netty4Message.Data", str1)
        Netty4Message.Data(f, updateWindow)
      case f: Http2HeadersFrame if f.isEndStream =>
        log.debug("%s Netty4Message.Trailers", str1)
        Netty4Message.Trailers(f.headers)
      case f =>
        log.debug("%s invalid stream frame: %s", str1, f)
        throw new IllegalArgumentException(s"invalid stream frame: ${f}")
    }
  }

  private[this] val updateWindow: Int => Future[Unit] = transport.updateWindow(streamId, _)

  /**
   * Write a `SendMsg`-typed [[Message]] to the remote.
   *
   * The outer future is satisfied initially to indicate that the
   * local message has been initiated (i.e. its HEADERS have been
   * sent). This first future is satisfied with a second future. The
   * second future is satisfied when the full local stream has been
   * written to the remote.
   *
   * If any write fails or is canceled, the entire stream is reset.
   *
   * If the stream is reset, writes are canceled.
   */
  def send(msg: SendMsg): Future[Future[Unit]] = {
    val str = s"[$prefix] send"
    val headersF = writeHeaders(str, msg.headers, msg.stream.isEmpty)
    val streamFF = headersF.map(_ => writeStream(str, msg.stream))

    val writeF = streamFF.flatten
    onReset(str).onFailure { fail =>
      val str1 = s"$str onReset.onFailure"
      log.debug("%s %s", str1, fail)
      writeF.raise(fail)
    }
    writeF.respond {
      case Return(_) =>
        val str1 = s"$str Return"
        log.debug(str1)
        closeLocal(str1)

      case Throw(StreamError.Remote(e)) =>
        val str1 = s"$str StreamError.Remote"
        val rst = e match {
          case rst: Reset => rst
          case _ => Reset.Cancel
        }
        log.debug("%s %s", str1, rst)
        remoteReset(str1, rst)

      case Throw(StreamError.Local(e)) =>
        val str1 = s"$str StreamError.Local"
        val rst = e match {
          case rst: Reset => rst
          case _ => Reset.Cancel
        }
        log.debug("%s %s", str1, rst)
        localReset(str1, rst)

      case Throw(e) =>
        val str1 = s"$str InternalError"
        log.debug("%s %s", str1, e)
        localReset(str1, Reset.InternalError)
    }

    streamFF
  }

  private[this] val writeHeaders: (String, Headers, Boolean) => Future[Unit] = { (str, hdrs, eos) =>
    stateRef.get match {
      case Closed(rst) =>
        val str1 = s"$str -- writeHeaders Closed"
        log.debug(str1)
        Future.exception(StreamError.Remote(rst))
      case LocalClosed(_) =>
        val str1 = s"$str -- writeHeaders LocalClosed"
        log.debug(str1)
        Future.exception(new IllegalStateException("writing on closed stream"))
      case LocalOpen() =>
        val str1 = s"$str -- writeHeaders LocalOpen"
        if (ConnectionHeaders.detect(hdrs)) {
          log.debug("%s exception", str1)
          Future.exception(StreamError.Local(Reset.ProtocolError))
        } else {
          log.debug("%s exception", str1)
          localResetOnCancel(str1, transport.write(streamId, hdrs, eos))
        }
    }
  }

  /** Write a request stream to the underlying transport */
  private[this] val writeStream: (String, Stream) => Future[Unit] = { (str, stream) =>
    val str1 = s"$str -- writeStream"
    def loop(): Future[Unit] =
      stream.read(str1).rescue(wrapLocalEx)
        .flatMap { f =>
          log.debug("%s writing frame isEnd=%s", str1, f.isEnd)
          writeFrame(str1, f).flatMap { _ =>
            if (!f.isEnd) loop() else Future.Unit
          }
        }

    localResetOnCancel(str1, loop())
  }

  private[this] def localResetOnCancel[T](str: String, f: Future[T]): Future[T] = {
    val str1 = s"$str -- localResetOnCancel"
    val p = new Promise[T]
    p.setInterruptHandler {
      case e =>
        log.debug("%s setInterruptHandler %s", str1, e)
        localReset(str, Reset.Cancel)
        f.raise(e)
    }
    f.proxyTo(p)
    p
  }

  private[this] val writeFrame: (String, Frame) => Future[Unit] = { (str, frame) =>
    val str1 = s"$str -- writeFrame"
    stateRef.get match {
      case Closed(rst) =>
        log.debug("%s Closed %s", str1, rst)
        Future.exception(StreamError.Remote(rst))
      case LocalClosed(_) =>
        log.debug("%s LocalClosed", str1)
        Future.exception(new IllegalStateException("writing on closed stream"))
      case LocalOpen() =>
        log.debug("%s LocalOpen", str1)
        statsReceiver.recordLocalFrame(frame)
        transport.write(streamId, frame).rescue(wrapRemoteEx)
          .before(frame.release().rescue(wrapLocalEx))
    }
  }
}

object Netty4StreamTransport {
  private lazy val log = Logger.get(getClass.getName)

  /** Helper: a state that supports Reset.  (All but Closed) */
  private trait ResettableState {
    def reset(str: String, rst: Reset): Unit
  }

  private val wrapLocalEx: PartialFunction[Throwable, Future[Nothing]] = {
    case e@StreamError.Local(_) =>
      log.debug("wrapLocalEx Local %s", e)
      Future.exception(e)
    case e@StreamError.Remote(_) =>
      log.debug("wrapLocalEx Remote %s", e)
      Future.exception(e)
    case e =>
      log.debug("wrapLocalEx Other %s", e)
      Future.exception(StreamError.Local(e))
  }

  private def wrapRemoteEx: PartialFunction[Throwable, Future[Nothing]] = {
    case e@StreamError.Local(_) =>
      log.debug("wrapRemoteEx Local %s", e)
      Future.exception(e)
    case e@StreamError.Remote(_) =>
      log.debug("wrapRemoteEx Remote %s", e)
      Future.exception(e)
    case e =>
      log.debug("wrapRemoteEx Other %s", e)
      Future.exception(StreamError.Remote(e))
  }

  private object NilStream extends Stream {
    override def isEmpty = true
    override def onEnd = Future.Unit
    override def read(str: String): Future[Frame] = {
      log.debug("%s read from NilStream", str)
      Future.exception(Reset.NoError)
    }
  }

  class StatsReceiver(underlying: FStatsReceiver) {
    private[this] val local = underlying.scope("local")
    private[this] val localDataBytes = local.stat("data", "bytes")
    private[this] val localDataFrames = local.counter("data", "frames")
    private[this] val localTrailersCount = local.counter("trailers")
    val localResetCount = local.counter("reset")
    val recordLocalFrame: Frame => Unit = {
      case d: Frame.Data =>
        localDataFrames.incr()
        localDataBytes.add(d.buf.length)
      case t: Frame.Trailers => localTrailersCount.incr()
    }

    private[this] val remote = underlying.scope("remote")
    private[this] val remoteDataBytes = remote.stat("data", "bytes")
    private[this] val remoteDataFrames = remote.counter("data", "frames")
    private[this] val remoteTrailersCount = remote.counter("trailers")
    val remoteResetCount = remote.counter("reset")
    val recordRemoteFrame: Frame => Unit = {
      case d: Frame.Data =>
        remoteDataFrames.incr()
        remoteDataBytes.add(d.buf.length)
      case _: Frame.Trailers => remoteTrailersCount.incr()
    }

  }

  object NullStatsReceiver extends StatsReceiver(FNullStatsReceiver)

  private class Client(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Request, Response] {

    override protected[this] val prefix =
      s"C L:${transport.localAddress} R:${transport.remoteAddress} S:${streamId}"

    override protected[this] def mkRecvMsg(headers: Http2Headers, stream: Stream): Response =
      Response(Netty4Message.Headers(headers), stream)
  }

  private class Server(
    override val streamId: Int,
    override protected[this] val transport: H2Transport.Writer,
    override protected[this] val statsReceiver: StatsReceiver
  ) extends Netty4StreamTransport[Response, Request] {

    override protected[this] val prefix =
      s"S L:${transport.localAddress} R:${transport.remoteAddress} S:${streamId}"

    override protected[this] def mkRecvMsg(headers: Http2Headers, stream: Stream): Request =
      Request(Netty4Message.Headers(headers), stream)
  }

  def client(
    id: Int,
    writer: H2Transport.Writer,
    stats: StatsReceiver = NullStatsReceiver
  ): Netty4StreamTransport[Request, Response] =
    new Client(id, writer, stats)

  def server(
    id: Int,
    writer: H2Transport.Writer,
    stats: StatsReceiver = NullStatsReceiver
  ): Netty4StreamTransport[Response, Request] =
    new Server(id, writer, stats)

}
