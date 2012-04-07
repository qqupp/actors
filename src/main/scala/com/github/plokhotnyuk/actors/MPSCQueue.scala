package com.github.plokhotnyuk.actors

import java.util.concurrent.atomic.AtomicReference
import annotation.tailrec

class MPSCQueue[T >: Null] {
  private[this] var tail = new Node[T](null)
  private[this] val head = new AtomicReference[Node[T]](tail)

  def offer(data: T) {
    val node = new Node[T](data)
    head.getAndSet(node).next = node
  }

  @tailrec
  final def poll(): T = {
    val next = tail.next
    if (next != null) {
      tail = next
      next.data
    } else {
      poll()
    }
  }
}

private[actors] class Node[T >: Null](val data: T) {
  @volatile private[actors] var next: Node[T] = _
}