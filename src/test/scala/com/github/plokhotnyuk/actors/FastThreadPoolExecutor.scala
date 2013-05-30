package com.github.plokhotnyuk.actors

import java.util.concurrent._
import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.lang.InterruptedException
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import com.github.plokhotnyuk.actors.FastThreadPoolExecutor._

/**
 * A high performance implementation of thread pool with fixed number of threads.
 *
 * Based on implementation of ThreadManager from JActor2:
 * https://github.com/laforge49/JActor2/blob/master/jactor-impl/src/main/java/org/agilewiki/jactor/impl/ThreadManagerImpl.java
 *
 * @param threadCount a number of worker threads in pool
 * @param threadFactory a factory to be used to build worker threads
 * @param handler the handler for internal worker threads that will be called
 *                in case of unrecoverable errors encountered while executing tasks.
 */
class FastThreadPoolExecutor(threadCount: Int = Runtime.getRuntime.availableProcessors(),
                             threadFactory: ThreadFactory = new ThreadFactory() {
                               def newThread(r: Runnable): Thread = new Thread(r)
                             },
                             handler: Thread.UncaughtExceptionHandler = null) extends AbstractExecutorService {

  private val closing = new AtomicInteger(0)
  private val taskRequests = new Semaphore(0)
  private val tasks = new ConcurrentLinkedQueue[Runnable]()
  private val threadTerminations = new CountDownLatch(threadCount)
  private val threads = {
    val tt = threadTerminations
    val tf = threadFactory
    val h = handler
    (1 to threadCount).map {
      _ =>
        val t = tf.newThread(new Runnable() {
          def run() {
            try {
              doWork()
            } catch {
              case ex: InterruptedException => // ignore
            } finally {
              tt.countDown()
            }
          }
        })
        if (h != null) t.setUncaughtExceptionHandler(h)
        t
    }
  }

  threads.foreach(_.start())

  def shutdown() {
    shutdownNow()
    awaitTermination(0, TimeUnit.MILLISECONDS)
  }

  def shutdownNow(): util.List[Runnable] = {
    closing.set(1)
    taskRequests.release(threads.size)
    threads.foreach(_.interrupt())
    drainRemainingTasks(new util.LinkedList[Runnable]())
  }

  def isShutdown: Boolean = !isRunning

  def isTerminated: Boolean = threadTerminations.getCount == 0

  def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = threadTerminations.await(timeout, unit)

  def execute(command: Runnable) {
    tasks.offer(command)
    taskRequests.release()
  }

  @tailrec
  private def doWork() {
    if (isRunning) {
      taskRequests.acquire()
      val t = tasks.poll()
      if (t ne null) t.run()
      doWork()
    }
  }

  private def isRunning: Boolean = closing.intValue() == 0

  @tailrec
  private def drainRemainingTasks(ts: util.List[Runnable]): util.List[Runnable] = {
    val t = tasks.poll()
    if (t eq null) ts
    else {
      ts.add(t)
      drainRemainingTasks(ts)
    }
  }
}