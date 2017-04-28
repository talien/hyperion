import org.scalatest._
import hyperion._
import akka.actor._
import akka.testkit._
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util._
import scala.concurrent.Await

class TestPipeCreatorCase (_system: ActorSystem) extends TestKit(_system) with ImplicitSender
with WordSpecLike with MustMatchers with BeforeAndAfterAll {
 
	def this() = this(ActorSystem("hyperion"))
	
	override def afterAll {
	   _system.shutdown()
	}
	
	"PipeCreator" must {
	  implicit val timeout = Timeout(1000 millis)
	  "be able to create and query Tail objects" in {
	    val pipeManager = system.actorOf(Props(new PipeCreator), "creator")
	    val options = NodeProperty("almafa",10,10,PipeOptions("tail","tail",Map("backlog" -> "1")))
	    pipeManager ! Create(options)
	    system.actorSelection("akka://hyperion/user/pipe_tail") ! Message.empty
	    val result = Await.result(pipeManager ? TailQuery("tail"), timeout.duration).asInstanceOf[List[Message]]
	    assert(result == List[Message](Message.empty))
	  }
	}

}
