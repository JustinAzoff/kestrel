/*
 * Copyright (c) 2008 Robey Pointer <robeypointer@lag.net>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package net.lag.kestrel

import java.net.InetSocketAddress
import java.util.Properties
import java.util.concurrent.{CountDownLatch, Executors, ExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import scala.actors.{Actor, Scheduler}
import scala.actors.Actor._
import scala.collection.mutable
import org.apache.mina.core.session.IoSession
import org.apache.mina.filter.codec.ProtocolCodecFilter
import org.apache.mina.transport.socket.SocketAcceptor
import org.apache.mina.transport.socket.nio.{NioProcessor, NioSocketAcceptor}
import net.lag.configgy.{Config, ConfigMap, Configgy, RuntimeEnvironment}
import net.lag.logging.Logger
import net.lag.naggati.IoHandlerActorAdapter


class Counter {
  private var value = new AtomicLong(0)

  def get() = value.get
  def set(n: Int) = value.set(n)
  def incr = value.addAndGet(1)
  def incr(n: Int) = value.addAndGet(n)
  def decr = value.addAndGet(-1)
  override def toString = value.get.toString
}


object KestrelStats {
  val bytesRead = new Counter
  val bytesWritten = new Counter
  val sessions = new Counter
  val totalConnections = new Counter
  val getRequests = new Counter
  val setRequests = new Counter
  val incrRequests = new Counter
  val sessionID = new Counter
}


object Kestrel {
  private val log = Logger.get
  val runtime = new RuntimeEnvironment(getClass)

  var queues: QueueCollection = null

  private val _expiryStats = new mutable.HashMap[String, Int]
  private val _startTime = Time.now

  var acceptorExecutor: ExecutorService = null
  var acceptor: SocketAcceptor = null

  private val deathSwitch = new CountDownLatch(1)


  def main(args: Array[String]): Unit = {
    runtime.load(args)
    startup(Configgy.config)
  }

  def configure(c: Option[ConfigMap]) = {
    for (config <- c) {
      PersistentQueue.maxJournalSize = config.getInt("max_journal_size", 16 * 1024 * 1024)
      PersistentQueue.maxMemorySize = config.getInt("max_memory_size", 128 * 1024 * 1024)
      PersistentQueue.maxJournalOverflow = config.getInt("max_journal_overflow", 10)
    }
  }

  def startup(config: Config) = {
    val listenAddress = config.getString("host", "0.0.0.0")
    val listenPort = config.getInt("port", 22122)
    queues = new QueueCollection(config.getString("queue_path", "/tmp"), config.configMap("queues"))
    configure(Some(config))
    config.subscribe(configure _)

    acceptorExecutor = Executors.newCachedThreadPool()
    acceptor = new NioSocketAcceptor(acceptorExecutor, new NioProcessor(acceptorExecutor))

    // mina garbage:
    acceptor.setBacklog(1000)
    acceptor.setReuseAddress(true)
    acceptor.getSessionConfig.setTcpNoDelay(true)
    acceptor.getFilterChain.addLast("codec", new ProtocolCodecFilter(memcache.Codec.encoder,
      memcache.Codec.decoder))
    acceptor.setHandler(new IoHandlerActorAdapter((session: IoSession) => new KestrelHandler(session, config)))
    acceptor.bind(new InetSocketAddress(listenAddress, listenPort))

    log.info("Kestrel started.")

    // make sure there's always one actor running so scala 272rc6 doesn't kill off the actors library.
    actor {
      deathSwitch.await
    }
  }

  def shutdown = {
    log.info("Shutting down!")
    queues.shutdown
    acceptor.unbind
    acceptor.dispose
    Scheduler.shutdown
    acceptorExecutor.shutdown
    // the line below causes a 1s pause in unit tests. :(
    acceptorExecutor.awaitTermination(5, TimeUnit.SECONDS)
    deathSwitch.countDown
  }

  def uptime = (Time.now - _startTime) / 1000
}
