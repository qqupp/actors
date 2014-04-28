package akka.actor

import java.util.concurrent.atomic.AtomicReference
import com.typesafe.config.Config
import akka.dispatch._

class NonBlockingBoundedMailbox(bound: Int = Int.MaxValue) extends MailboxType with ProducesMessageQueue[MessageQueue] {
  if (bound <= 0) throw new IllegalArgumentException("Mailbox bound should be greater than 0")

  def this(settings: ActorSystem.Settings, config: Config) = this(config.getInt("mailbox-bound"))

  override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue = new NBBQ(bound)
}

private class NBBQ(bound: Int) extends AtomicReference(new NBBQNode) with MessageQueue with MultipleConsumerSemantics {
  private val tail = new AtomicReference(get)

  override def enqueue(receiver: ActorRef, handle: Envelope): Unit =
    if (!offer(new NBBQNode(handle))) {
      val deadLetter = DeadLetter(handle.message, handle.sender, receiver)
      receiver.asInstanceOf[InternalActorRef].provider.deadLetters.tell(deadLetter, handle.sender)
    }

  override def dequeue(): Envelope = poll(tail)

  override def numberOfMessages: Int = get.count - tail.get.count

  override def hasMessages: Boolean = get ne tail.get

  override def cleanUp(o: ActorRef, dl: MessageQueue): Unit = {
    var envelope = dequeue()
    while (envelope ne null) {
      dl.enqueue(o, envelope)
      envelope = dequeue()
    }
  }

  @annotation.tailrec
  private def offer(n: NBBQNode): Boolean = {
    val tc = tail.get.count
    val h = get
    val hc = h.count
    if (hc - tc < bound) {
      n.count = hc + 1
      if (compareAndSet(h, n)) {
        h.lazySet(n)
        true
      } else offer(n)
    } else false
  }

  @annotation.tailrec
  private def poll(t: AtomicReference[NBBQNode]): Envelope = {
    val tn = t.get
    val n = tn.get
    if (n ne null) {
      if (t.compareAndSet(tn, n)) {
        val e = n.handle
        n.handle = null // to avoid possible memory leak when queue is empty
        e
      } else poll(t)
    } else null
  }
}

private class NBBQNode(var handle: Envelope = null) extends AtomicReference[NBBQNode] {
  var count: Int = _
}