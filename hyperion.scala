import akka.actor._
import com.typesafe.config.ConfigFactory
import hyperion._

object Hyperion extends App
{
     val customConf = ConfigFactory.parseString("""
     akka.actor {
         default-dispatcher {
              executor = "thread-pool-executor"
    		  # Configuration for the thread pool
              thread-pool-executor {
    		  # minimum number of threads to cap factor-based core number to
                  core-pool-size-min = 2
    		 	  # No of core threads ... ceil(available processors * factor)
                  core-pool-size-factor = 2.0
    		 	  # maximum number of threads to cap factor-based number to
                  core-pool-size-max = 10
              }
             throughput = 1000
         }
     }
     akka.actor.default-mailbox {
    	mailbox-type = "akka.dispatch.BoundedMailbox"
        mailbox-capacity = 100
        mailbox-push-timeout-time = 10s  
     }
     """)

    println("Hyperion starting up")
    val system = ActorSystem("hyperion", ConfigFactory.load(customConf))
    val pipeManager = system.actorOf(Props(new PipeCreator), "creator")
    HyperionREST.start(system, pipeManager, "localhost", 8080, "frontend")
    println("Hyperion started")
}