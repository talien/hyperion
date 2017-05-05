import akka.actor._
import com.typesafe.config.ConfigFactory
import hyperion._

object Hyperion extends App
{
     /*val customConf = ConfigFactory.parseString("""
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
     }"""
     """akka.actor.default-mailbox {
    	mailbox-type = "akka.dispatch.BoundedMailbox"
        mailbox-capacity = 100
        mailbox-push-timeout-time = 0s  
     }
     """)*/

    println("Hyperion starting up")
    //val system = ActorSystem("hyperion", ConfigFactory.load(customConf))
    val system = ActorSystem("hyperion")
    val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("hyperion"))), "creator")
    HyperionREST.start(system, pipeManager, "0.0.0.0", 8080, "frontend")
    println("Hyperion started")
}
