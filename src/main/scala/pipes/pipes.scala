import akka.util.{ByteString, ByteStringBuilder}
import java.net.InetSocketAddress
import akka.actor._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.{ArrayBuffer,ListBuffer}
import akka.pattern.{ask, pipe}
import akka.dispatch.RequiresMessageQueue
import akka.dispatch.BoundedMessageQueueSemantics
import akka.io._
import akka.io.Tcp._

import java.io._


package hyperion {

  case class Terminate()

  case class PipeConnectionUpdate(add: Map[String, ActorSelection], remove: List[String])

  case class Query()

  case class Reset()

  case class Tick()

  case class PipeShutdown(ids: List[String])

  case class Disconnected(id: String)

  abstract class Pipe extends Actor with ActorLogging
    //with RequiresMessageQueue[BoundedMessageQueueSemantics] {
  {
    def selfId : String
    val nextPipes = scala.collection.mutable.HashMap.empty[String,ActorSelection]
    val disconnected = scala.collection.mutable.ListBuffer.empty[String]
    val shutdownList = scala.collection.mutable.ListBuffer.empty[String]

    def pipeShutDownhook() = {}

    def receiveControl: PartialFunction[Any, Unit] = {
      case PipeConnectionUpdate(add, remove) => {
        add map { case (id, pipe) =>
          nextPipes.put(id, pipe)
        }
        remove map {(id) => {
          nextPipes.get(id).get ! Disconnected(selfId)
          nextPipes.remove(id)
        }}
      }

      case Disconnected(id) => {
        disconnected += id
        if (shutdownList.length == disconnected.length) {
          pipeShutDownhook()
          context.stop(self)
        }
      }

      case PipeShutdown(fromPipes) => {
        shutdownList ++= fromPipes
        if (shutdownList.length == disconnected.length) {
          pipeShutDownhook()
          context.stop(self)
        }
      }
    }

    override def preStart() = {
      log.info("Actor started:" + this.toString() + " " + self.path.toString);
    }

    def receive = receiveControl orElse process

    def process: PartialFunction[Any, Unit]

    def propagateMessage(message: Message) =
      if (!nextPipes.isEmpty) {
        nextPipes.foreach(data => {
          data._2 ! message
        })
      }
  }

  trait Tickable {
    this: Actor =>
    var cancellable: Any = Nil

    def startTicking(initialDelay: FiniteDuration, period: FiniteDuration) = {
      cancellable = context.system.scheduler.schedule(initialDelay, period) {
        this.self ! Tick
      }
    }

    def stopTicking() = {
      cancellable.asInstanceOf[akka.actor.Cancellable].cancel
    }
  }

  class Printer(id: String) extends Pipe {
    def selfId = id
    def process = {
      case Message(data) => println(data("MESSAGE"))
    }
  }




}
