package com.github.plokhotnyuk.actors

import akka.dispatch.ForkJoinExecutorConfigurator.AkkaForkJoinPool
import com.github.plokhotnyuk.actors.BenchmarkSpec._
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory._
import java.util.concurrent._
import org.agrona.concurrent.HighResolutionTimer
import org.HdrHistogram.Histogram
import org.scalatest._

abstract class BenchmarkSpec extends FreeSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  override def beforeAll(): Unit = {
    HighResolutionTimer.enable()
  }

  override def afterEach(): Unit = {
    usedMemory(0.1) // GC
    info("")
  }

  override def afterAll(): Unit = {
    shutdown()
    HighResolutionTimer.disable()
  }

  def shutdown(): Unit

  def timed[A](n: Int, printAvgLatency: Boolean = false)(benchmark: => A): A = {
    val t = System.nanoTime()
    val ct = osMXBean.getProcessCpuTime
    val r = benchmark
    val cd = osMXBean.getProcessCpuTime - ct
    val d = System.nanoTime() - t
    info(f"$n%,d ops")
    info(f"$d%,d ns")
    if (printAvgLatency) info(f"${d / n}%,d ns/op")
    else info(f"${(n * 1000000000L) / d}%,d ops/s")
    info(f"${(cd * 100.0) / d / processors}%2.1f %% of CPU usage")
    r
  }

  def latencyTimed[A](n: Int)(benchmark: LatencyHistogram => A): A = {
    val h = new LatencyHistogram
    val t = System.nanoTime()
    val ct = osMXBean.getProcessCpuTime
    val r = benchmark(h)
    val cd = osMXBean.getProcessCpuTime - ct
    val d = System.nanoTime() - t
    info(f"$n%,d ops")
    info(f"$d%,d ns")
    List(0.0, 0.5, 0.9, 0.99, 0.999, 0.9999, 0.99999, 1.0).foreach {
      x => info(f"p($x%1.5f) = ${h.getValueAtPercentile(x * 100)}%8d ns/op")
    }
    info(f"${(cd * 100.0) / d / processors}%2.1f %% of CPU usage")
    r
  }

  def footprintedAndTimed[A](n: Int)(benchmark: => A): A = {
    val u = usedMemory()
    val r = timed(n)(benchmark)
    val m = usedMemory() - u
    val b = bytesPerInstance(m, n)
    info(f"$b%,d bytes per instance")
    r
  }

  def footprintedAndTimedCollect[A](n: Int)(construct: () => A, teardown: => Unit = ()): Seq[A] = {
    val r = Array.ofDim(n).asInstanceOf[Array[A]]
    val u = usedMemory()
    timed(n, printAvgLatency = true) {
      val as = r
      var i = n
      while (i > 0) {
        i -= 1
        as(i) = construct()
      }
    }
    teardown
    val m = usedMemory() - u
    val b = bytesPerInstance(m, n)
    info(f"$b%,d bytes per instance")
    r
  }
}

object BenchmarkSpec {
  private val processors = Runtime.getRuntime.availableProcessors
  private val executorServiceType = System.getProperty("benchmark.executorServiceType", "java-forkjoin-pool")
  private val poolSize = System.getProperty("benchmark.poolSize", processors.toString).toInt
  private val osMXBean = newPlatformMXBeanProxy(getPlatformMBeanServer, OPERATING_SYSTEM_MXBEAN_NAME, classOf[OperatingSystemMXBean])
  private val memoryMXBean = getMemoryMXBean
  println(s"Executor service type: $executorServiceType")

  val parallelism: Int = System.getProperty("benchmark.parallelism", processors.toString).toInt

  def roundToParallelism(n: Int): Int = (n / parallelism) * parallelism

  def createExecutorService(size: Int = poolSize): ExecutorService =
    executorServiceType match {
      case "akka-forkjoin-pool" => new AkkaForkJoinPool(size, akka.dispatch.forkjoin.ForkJoinPool.defaultForkJoinWorkerThreadFactory, null)
      case "java-forkjoin-pool" => new ForkJoinPool(size, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true)
      case "lbq-thread-pool" => new ThreadPoolExecutor(size, size, 1, TimeUnit.HOURS,
        new LinkedBlockingQueue[Runnable](), Executors.defaultThreadFactory(), new ThreadPoolExecutor.DiscardPolicy())
      case "abq-thread-pool" => new ThreadPoolExecutor(size, size, 1, TimeUnit.HOURS,
        new ArrayBlockingQueue[Runnable](1000000), Executors.defaultThreadFactory(), new ThreadPoolExecutor.DiscardPolicy())
      case _ => throw new IllegalArgumentException("Unsupported value of benchmark.executorServiceType property")
    }

  def bytesPerInstance(m: Long, n: Int): Int = Math.round(m.toDouble / n).toInt

  def usedMemory(precision: Double = 0.001): Long = {
    def getUsed: Long = memoryMXBean.getHeapMemoryUsage.getUsed

    @annotation.tailrec
    def getHeapMemoryUsage(prev: Long, i: Int = 10): Long = {
      Thread.sleep(10)
      val curr = getUsed
      val diff = prev - curr
      if (diff < 0 || diff > precision * curr) getHeapMemoryUsage(curr)
      else if (i > 0) getHeapMemoryUsage(curr, i - 1)
      else curr
    }

    val prev = getUsed
    System.gc()
    getHeapMemoryUsage(prev)
  }

  def fullShutdown(e: ExecutorService): Unit = {
    e.shutdownNow()
    e.awaitTermination(1, TimeUnit.MINUTES)
  }
}

class LatencyHistogram extends Histogram(1000000000L, 2) {
  private var t: Long = 0

  def record(): Unit = {
    val t1 = System.nanoTime()
    if (t != 0) recordValue(t1 - t)
    t = t1
  }
}