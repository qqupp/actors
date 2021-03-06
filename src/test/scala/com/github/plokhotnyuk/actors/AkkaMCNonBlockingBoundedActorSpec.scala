package com.github.plokhotnyuk.actors

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory._

class AkkaMCNonBlockingBoundedActorSpec extends AkkaBoundedActorSpec {
  override def config: Config = load(parseString(
    """
      akka {
        log-dead-letters = 0
        log-dead-letters-during-shutdown = off
        actor {
          unstarted-push-timeout = 100s
          benchmark-dispatcher {
            executor = "com.github.plokhotnyuk.actors.CustomExecutorServiceConfigurator"
            throughput = 1024
            mailbox-type = "akka.dispatch.MultiConsumerNonBlockingBoundedMailbox"
            mailbox-capacity = 10000000
          }
          benchmark-dispatcher-2 {
            executor = "com.github.plokhotnyuk.actors.CustomExecutorServiceConfigurator"
            throughput = 1024
            mailbox-type = "akka.dispatch.MultiConsumerNonBlockingBoundedMailbox"
            mailbox-capacity = 1
          }
        }
      }
    """))
}