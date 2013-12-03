import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.{ ask, pipe }
import hyperion._
import spray.can.Http
import spray.http._
import akka.io.IO
import spray.util._
import spray.httpx._
import MediaTypes._
import spray.routing._
import spray.json._
import DefaultJsonProtocol._
import spray.routing.directives.CachingDirectives._
import spray.httpx.SprayJsonSupport._

package hyperion {
	object PipeOptionsJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {
	  implicit val pipeOptionsFormat = jsonFormat3(PipeOptions)
	}
	
	object MessageJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {
	  implicit val messageFormat = jsonFormat1(Message.apply)
	}
		
	object HyperionREST {
	  def start(implicit system: ActorSystem, pipeManager: ActorRef, interface : String, port: Int, staticDirectory: String) = {
	    val myListener = system.actorOf(Props(new HyperionHttp(pipeManager, staticDirectory)), name = "hyperion-http") 
	    IO(Http) ! Http.Bind(myListener, interface = interface, port = port)
        println("REST interface initialized")
	  }
	}
	
	class HyperionHttp(val pipeCreator: ActorRef, val staticDirectory: String) extends HttpServiceActor with ActorLogging {
	   
	   implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
	   
	   def getCounter(name: String) =
	   {
	     Await.result(pipeCreator ? CounterQuery(name), timeout.duration).asInstanceOf[Integer]
	   }
	   
	   def getTail(name: String) =
	   {
	     Await.result(pipeCreator ? TailQuery(name), timeout.duration).asInstanceOf[List[Message]]
	   }
	  
	   def receive = runRoute {
	    
	    path("rest" / "counter" / Segment) { name =>
	      get {
	        val result = getCounter(name)
	        complete(result.toString)
	      }
	    } ~
	    path("rest" / "tail" / Segment) { name =>
	      get {
	        import MessageJsonProtocol._
	        respondWithMediaType(`application/json`) {
	        	val result = getTail(name)
	        	complete (result)
	        }
	      }
	    } ~
	    path("rest" / "shutdown") {
	      get { ctx =>
	        ctx.complete {
	           context.system.shutdown()
	           "Stopped"
	        }
	        
	      }
	    } ~ 
	    path("rest" / "create") {
	      import PipeOptionsJsonProtocol._
	      post {
	        entity(as[PipeOptions]) {
	          pipeoptions => {
			    pipeCreator ! Create(pipeoptions)
			    println(pipeoptions)
			    complete("Hello")
	          }
	        }
	      }
	    } ~ 
	    path("rest" / "join" / Segment / Segment) { (from, to) =>
	      (get|post) {
	        pipeCreator ! Join(from, to)
	        println(from, to)
	        complete("hello")
	      }
	    } ~
	    pathPrefix("html") {
	      get {
	    	  getFromDirectory(staticDirectory)
	      }
	    }
	        
	  }
	}
}