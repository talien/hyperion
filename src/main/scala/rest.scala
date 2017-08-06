import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.{ask, pipe}
import hyperion._
import akka.io.IO
import scala.util.{Success, Failure}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.ContentType
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import scala.concurrent.Future

package hyperion {

  import akka.http.scaladsl.marshalling.Marshaller

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

    implicit val mapMarshaller: ToEntityMarshaller[Map[String, Any]] = Marshaller.opaque { map =>
      HttpEntity(ContentType(MediaTypes.`application/json`), map.toString)
    }

    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))

    def getTail(pipeCreator: ActorRef, name: String) : Future[List[Message]] = {
      (pipeCreator ? TailQuery(name)).asInstanceOf[Future[List[Message]]]
    }

    def getStats(pipeCreator: ActorRef, name: String) : Future[Map[String, Int]] = {
      (pipeCreator ? StatsQueryApi(name)).asInstanceOf[Future[Map[String, Int]]]
    }

    def getAllStats(pipeCreator: ActorRef) : Future[Map[String, Map[String, Int]]] = {
      (pipeCreator ? AllStatsQuery).asInstanceOf[Future[Map[String, Map[String, Int]]]]
    }

    def route(implicit system: ActorSystem, pipeCreator: ActorRef, staticDirectory: String) = {
      path("api" / "shutdown") {
        post {
          complete {
            system.terminate()
            "OK"
          }

        }
      } ~
        path("api" / "stats" / Segment) { name =>
          get {
            import spray.json.DefaultJsonProtocol._
            onSuccess(getStats(pipeCreator, name)) { value => complete(StatusCodes.OK -> value) }

          }
        } ~
        path("api" / "stats") {
          get {Marshaller
            onSuccess(getAllStats(pipeCreator)) { value => complete(value) }
          }
        } ~
        path("api" / "tail" / Segment) { name =>
          get {
            import MessageJsonProtocol._
            onSuccess(getTail(pipeCreator, name)) { value =>
              complete(value)
            }
          }
        } ~
        path("api" / "config") {
          import ConfigJsonProtocol._
          get {
            complete {
              (pipeCreator ? QueryConfig).mapTo[Config]
            }
          } ~
            post {
              entity(as[Config]) {
                hyperionConfig => {
                  onComplete(pipeCreator ? UploadConfig(hyperionConfig)) {
                    case Success(_) => complete(StatusCodes.OK -> "OK")
                    case Failure(e) => complete(StatusCodes.BadRequest -> e.getMessage())
                  }

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

    def start(implicit system: ActorSystem, pipeManager: ActorRef, interface: String, port: Int, staticDirectory: String) = {
      implicit val materializer = ActorMaterializer()
      val bindingFuture = Http().bindAndHandle(route(system, pipeManager, staticDirectory), interface, port)
      println("REST interface initialized")
    }
  }

}
