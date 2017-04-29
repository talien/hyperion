import akka.util.{ByteString, ByteStringBuilder}
import java.net.InetSocketAddress
import akka.actor._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.ArrayBuffer
import akka.pattern.{ask, pipe}
import akka.dispatch.RequiresMessageQueue
import akka.dispatch.BoundedMessageQueueSemantics
import akka.io._
import akka.io.Tcp._
import com.github.nscala_time.time.Imports._
import org.joda.time.format._
import java.io._

package hyperion {

  case class LogLine(line: String)

  case class Terminate()

  case class AddPipe(actor: ActorSelection)

  case class Query()

  case class Reset()

  case class Tick()

  abstract class Pipe extends Actor
    //with RequiresMessageQueue[BoundedMessageQueueSemantics] {
    {
    val next = ArrayBuffer[ActorSelection]()

    def receiveControl: PartialFunction[Any, Unit] = {
      case AddPipe(nextActor) =>
        next += nextActor
    }

    override def preStart() = {
      System.out.println("Actor started:" + this.toString() + " " + self.path.name);
    }

    def receive = receiveControl orElse process

    def process: PartialFunction[Any, Unit]

    def propagateMessage(message: Message) =
      if (!next.isEmpty) {
        next.foreach(actor =>
          actor ! message
        )
      }
  }

  class Printer extends Pipe {
    def process = {
      case Message(data) => println(data("MESSAGE"))
    }
  }

  class Filter(name: String, value: String) extends Pipe {
    def process = {
      case Message(data) =>
        if (data.contains(name) && data(name).matches(value)) propagateMessage(Message(data))
    }
  }

  class Rewrite(name: String, regexp: String, value: String) extends Pipe {
    def process = {
      case Message(data) =>
        if (data.contains(name)) propagateMessage(
          Message(data.updated(name, data(name) replaceAll(regexp, value)))
        )
        else propagateMessage(Message(data))
    }
  }

  abstract class Counter extends Pipe {
    var counter = 0

    def process = {
      case Message(data) =>
        count(data)
      case Reset =>
        counter = 0
      case Query => {
        sender ! counter
      }
    }

    def count(data: Map[String, String])
  }

  class MessageCounter extends Counter {
    def count(data: Map[String, String]) = {
      counter = counter + 1
    }
  }

  class FieldValueCounter(name: String, value: String) extends Counter {
    def count(data: Map[String, String]) = {
      if (data.contains(name) && data(name).matches(value)) counter = counter + 1
    }
  }

  class FieldStatistics(name: String) extends Pipe {
    val stats: scala.collection.mutable.Map[String, Int] = scala.collection.mutable.Map[String, Int]()

    def process = {
      case Message(data) =>
        if (data.contains(name)) {
          val value = stats.getOrElse(data(name), 0)
          stats.update(data(name), value + 1)
        }
      case Query => {
        sender ! stats.toMap
      }
    }
  }

  class AverageCounter(counter: ActorSelection, tick: FiniteDuration, backlogsize: Int) extends Actor {
    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
    var backlog = List[Int]()
    var cancellable: Any = Nil
    var lastData = 0

    def updateBacklog = {
      val currentData = Await.result(counter ? Query, timeout.duration).asInstanceOf[Integer]
      backlog = (currentData - lastData) :: (backlog take (backlogsize - 1))
      lastData = currentData
    }

    override def preStart = {
      super.preStart()
      cancellable = context.system.scheduler.schedule(FiniteDuration(0, SECONDS), tick) {
        this.self ! Tick
      }
    }

    def countAverage = if (backlog.size != 0) (backlog.sum / backlog.size) else 0

    def receive = {
      case Query => sender ! countAverage
      case Tick => updateBacklog
    }

    override def postStop = cancellable.asInstanceOf[akka.actor.Cancellable].cancel
  }

  class Tail(backlogSize: Int) extends Pipe {
    var messageList = List[Message]()

    def process = {
      case msg: Message => messageList = msg :: (messageList take (backlogSize - 1))
      case Query => sender ! messageList
    }
  }

  class FileDestination(fileName: String, template: String) extends Pipe {
    val writer = new BufferedWriter(new OutputStreamWriter(
      new FileOutputStream(fileName), "utf-8"))
    var cancellable: Any = Nil
    var lastMessage = DateTime.now
    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
    val msgTemplate = if (template == "") new MessageTemplate("<$PRIO> $DATE $HOST $PROGRAM $PID : $MESSAGE \n") else new MessageTemplate(template)

    override def preStart() = {
      super.preStart()
      cancellable = context.system.scheduler.schedule(FiniteDuration(0, SECONDS), FiniteDuration(1, SECONDS)) {
        this.self ! Tick
      }

    }

    override def postStop() = {
      cancellable.asInstanceOf[akka.actor.Cancellable].cancel
      writer.close()
      super.postStop()
    }

    def process = {
      case msg: Message => {
        writer.write(msgTemplate.format(msg))
        lastMessage = DateTime.now
      }

      case Tick => {

        if (DateTime.now.minus(lastMessage.getMillis).getMillis > 1000L) {
          writer.flush()
        }

      }
    }
  }

  class Parser extends Pipe {

    def process = {
      case LogLine(line) => {
        try {
          parse(line)
        } catch {
          case e:Exception=>
            println(line)
        }
        sender ! Ack
      }
    }

    def parse(message: String) = {
      val data = parseMessage(message)
      propagateMessage(Message(data))
    }

  }

  class ServerActor (parser: ActorRef, port: Int) extends Actor {
    import context.system

    IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress("0.0.0.0", port), pullMode = true)

    def listening(listener: ActorRef): Receive = {
      case Connected(remote, local) =>
        val connection = sender()
        val handler = context.actorOf(Props(new ReceiverActor(remote, connection, parser)))
        sender() ! Register(handler, keepOpenOnPeerClosed = true)
        listener ! ResumeAccepting(batchSize = 1)
    }

    def receive = {

      case Bound(localAddress) =>
        // Accept connections one by one
        sender() ! ResumeAccepting(batchSize = 1)
        context.become(listening(sender()))
    }

  }
  case object Ack extends Event

  class ReceiverActor(remote: InetSocketAddress, connection: ActorRef, parser: ActorRef) extends Actor with ActorLogging {
    val LF = ByteString("\n")
    var data_buffer = ByteString()
    var sent_message = 0
    var processed_message = 0

    override def preStart: Unit = connection ! ResumeReading

    def parseLines(buffer: ByteString): ByteString = {
        val endline = buffer.indexOfSlice(LF)
        if (endline >= 0)
          {
            val message = buffer.slice(0, endline).utf8String
            //log.info("Message: " + message+";");
            parser ! LogLine(message)
            sent_message += 1
            processed_message += 1
            parseLines(buffer.slice(endline + 1, buffer.length))
          }
          else
          {
            buffer
          }
    }

    def receive: Receive = {
      case Tcp.Received(data) =>
        /*val text = data.utf8String.trim
        log.debug("Received '{}' from remote address {}", text, remote)
        text match {
          case "close" => context.stop(self)
          //case _       => sender ! Tcp.Write(data)
        }*/
        //log.info(data.length.toString)
        connection ! SuspendReading
        data_buffer ++= data
        data_buffer = parseLines(data_buffer)
        //connection ! ResumeReading
      case _: Tcp.ConnectionClosed =>
        log.info("Stopping, because connection for remote address {} closed", remote)
        log.info("Remaining messages: {}", sent_message)
        log.info("Processed messages: {}", processed_message)
        log.info("Data buffer: {}", data_buffer)
        context.stop(self)
      case Terminated(`connection`) =>
        log.info("Stopping, because connection for remote address {} died", remote)
        context.stop(self)
      case Ack =>         
        sent_message -= 1
        if (sent_message == 0) {
          connection ! ResumeReading
        }
    }

  }

}
