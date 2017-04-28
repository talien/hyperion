import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.{ask, pipe}
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

  object NodePropertyJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {

    import PipeOptionsJsonProtocol._

    implicit val nodeFormat = jsonFormat4(NodeProperty)
  }

  object ConnectionJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {
    implicit val connFormat = jsonFormat2(Connection)
  }

  object ConfigJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {

    import NodePropertyJsonProtocol._
    import ConnectionJsonProtocol._

    implicit val configFormat = jsonFormat2(Config)
  }

  object HyperionREST {
    def start(implicit system: ActorSystem, pipeManager: ActorRef, interface: String, port: Int, staticDirectory: String) = {
      val myListener = system.actorOf(Props(new HyperionHttp(pipeManager, staticDirectory)), name = "hyperion-http")
      IO(Http) ! Http.Bind(myListener, interface = interface, port = port)
      println("REST interface initialized")
    }
  }

  class HyperionHttp(val pipeCreator: ActorRef, val staticDirectory: String) extends HttpServiceActor with ActorLogging {

    def printing : Directive0 = extract (identity) flatMap {ctx => { System.out.println(ctx.toString); pass} }

    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))

    def getCounter(name: String) = {
      Await.result(pipeCreator ? CounterQuery(name), timeout.duration).asInstanceOf[Int]
    }

    def getTail(name: String) = {
      pipeCreator ? TailQuery(name)
    }

    def getStats(name: String) = {
      pipeCreator ? StatsQuery(name)
    }

    def receive = runRoute {

          path("rest" / "counter" / Segment) { name =>

            get {
              import DefaultJsonProtocol._
              respondWithMediaType(`application/json`) {
                complete {
                  val result = getCounter(name)
                  result.toString
                }
              }

            }
          } ~
            path("rest" / "stats" / Segment) { name =>
              get {
                import DefaultJsonProtocol._
                respondWithMediaType(`application/json`) {
                  complete {
                    val result = getStats(name).mapTo[Map[String, Int]]
                    result
                  }
                }
              }
            } ~
            path("rest" / "tail" / Segment) { name =>
              get {
                import MessageJsonProtocol._
                respondWithMediaType(`application/json`) {
                  complete {
                    val result = getTail(name).mapTo[List[Message]]
                    result
                  }
                }
              }
            } ~
            path("rest" / "shutdown") {
              get {
                complete {
                  context.system.shutdown()
                  "Stopped"
                }

              }
            } ~
            path("rest" / "create") {
              import NodePropertyJsonProtocol._
              post {
                System.out.println()
                entity(as[NodeProperty]) {
                  node => {
                    pipeCreator ! Create(node)
                    println(node)
                    complete("OK")
                  }
                }
              }
            } ~
            path("rest" / "join" / Segment / Segment) { (from, to) =>
              (get | post) {
                pipeCreator ! Join(from, to)
                println(from, to)
                complete("hello")
              }
            } ~
            path("rest" / "config") {
              import ConfigJsonProtocol._
              get {
                respondWithMediaType(`application/json`) {
                  complete {
                    (pipeCreator ? QueryConfig).mapTo[Config]
                  }
                }
              } ~
              post {
                entity(as[Config]) {
                  config => {
                    pipeCreator ! UploadConfig(config)
                    complete("OK")
                  }
                }
              }
            } ~
            pathPrefix("html") {
              get {
                getFromDirectory(staticDirectory)
              }
            } ~
            pathSingleSlash {
              redirect("/html/index.html", StatusCodes.PermanentRedirect)
            }
        }

  }

}
