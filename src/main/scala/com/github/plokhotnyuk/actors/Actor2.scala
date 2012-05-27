package com.github.plokhotnyuk.actors

import scalaz.Scalaz
import Scalaz._
import scalaz.concurrent.{Strategy, Effect}
import annotation.tailrec
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

/**
 * Based on non-intrusive MPSC node-based queue, described by Dmitriy Vyukov:
 * http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
 */
final case class Actor2[A](e: A => Unit, onError: Throwable => Unit = throw (_), batchSize: Int = 1024)
                          (implicit val strategy: Strategy)  {
  private[this] var anyMsg: A = _ // Don't know how to simplify this
  private[this] val tail = new AtomicReference[Node[A]](new Node[A](anyMsg))
  private[this] val head = new AtomicReference[Node[A]](tail.get)
  private[this] val suspended = new AtomicLong(1L)

  val toEffect: Effect[A] = effect[A](a => this ! a)

  def apply(a: A) {
    this ! a
  }

  def !(a: A) {
    val node = new Node[A](a)
    head.getAndSet(node).lazySet(node)
    trySchedule()
  }

  private[this] def trySchedule() {
    if (suspended.compareAndSet(1L, 0L)) {
      schedule()
    }
  }

  private[this] def schedule(): Any =
    try {
      act(())
    } catch {
      case ex =>
        suspended.set(1L)
        throw new RuntimeException(ex)
    }

  private[this] val act: Effect[Unit] = effect {
    (u: Unit) =>
      val tailNode = batchHandle(tail.get, batchSize)
      tail.lazySet(tailNode)
      if (tailNode.get ne null) {
        schedule()
      } else {
        suspended.set(1L)
        if (tail.get.get ne null) {
          trySchedule()
        }
      }
  }

  @tailrec
  private[this] def batchHandle(curr: Node[A], i: Int): Node[A] = {
    val next = curr.get
    if (next ne null) {
      handle(next.a)
      if (i > 0) {
        batchHandle(next, i - 1)
      } else next
    } else curr
  }

  private[this] def handle(a: A) {
    try {
      e(a)
    } catch {
      case ex => onError(ex)
    }
  }
}

trait Actors2 {
  def actor2[A](e: A => Unit, err: Throwable => Unit = throw (_), batchSize: Int = 1024)(implicit s: Strategy): Actor2[A] = Actor2[A](e, err, batchSize)

  implicit def ActorFrom2[A](a: Actor2[A]): A => Unit = a ! _
}

object Scalaz2 extends Actors2
