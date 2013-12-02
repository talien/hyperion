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
	
	object HyperionREST {
	  def start(implicit system: ActorSystem, pipeManager: ActorRef, interface : String, port: Int) = {
	    val myListener = system.actorOf(Props(new HyperionHttp(pipeManager)), name = "hyperion-http") 
	    IO(Http) ! Http.Bind(myListener, interface = interface, port = port)
        println("REST interface initialized")
	  }
	}
	
	class HyperionHttp(val pipeCreator: ActorRef) extends HttpServiceActor with ActorLogging {
	   import PipeOptionsJsonProtocol._
	   implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
	   
	   def getCounter(name: String) =
	   {
	     Await.result(pipeCreator ? CounterQuery(name), timeout.duration).asInstanceOf[Integer]
	   }
	  
	   def receive = runRoute {
	    
	    path("ping") {
	      get {
	        complete("PONG")
	      }
	    } ~
	    path("counter" / Segment) { name =>
	      get {
	        val result = getCounter(name)
	        complete(result.toString)
	      }
	    } ~
	    path("shutdown") {
	      get { ctx =>
	        ctx.complete {
	           context.system.shutdown()
	           "Stopped"
	        }
	        
	      }
	    } ~ 
	    path("create") {
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
	    path("join" / Segment / Segment) { (from, to) =>
	      (get|post) {
	        pipeCreator ! Join(from, to)
	        println(from, to)
	        complete("hello")
	      }
	    }
	    
	     
	  }
	  
	  lazy val index = HttpResponse(
	    entity = HttpEntity(`text/html`,
	      <html>
        <body>
          It works!
        </body>
      </html>.toString()
	    )
	  )
	}
}