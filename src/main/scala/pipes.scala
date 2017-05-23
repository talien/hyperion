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
import com.github.nscala_time.time.Imports._
import org.joda.time.format._
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

  class Printer(id: String) extends Pipe {
    def selfId = id
    def process = {
      case Message(data) => println(data("MESSAGE"))
    }
  }

  class Filter(id:String, name: String, value: String) extends Pipe {
    def selfId = id
    def process = {
      case Message(data) =>
        if (data.contains(name) && data(name).matches(value)) propagateMessage(Message(data))
    }
  }

  class Rewrite(id:String, name: String, regexp: String, value: String) extends Pipe {
    def selfId = id
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

  class MessageCounter(id:String) extends Counter {
    def selfId = id
    def count(data: Map[String, String]) = {
      counter = counter + 1
    }
  }

  class FieldValueCounter(id:String, name: String, value: String) extends Counter {
    def selfId = id
    def count(data: Map[String, String]) = {
      if (data.contains(name) && data(name).matches(value)) counter = counter + 1
    }
  }

  class FieldStatistics(id: String, name: String) extends Pipe {
    def selfId = id
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

  class AverageCounter(id: String, counter: ActorSelection, tick: FiniteDuration, backlogsize: Int) extends Actor with ActorLogging {
    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
    def selfId = id
    var backlog = List[Int]()
    var cancellable: Any = Nil
    var lastData = 0

    def updateBacklog = {
      log.debug("Updating backlog in " + self.path.toString)
      val currentData = Await.result(counter ? Query, timeout.duration).asInstanceOf[Integer]
      backlog = (currentData - lastData) :: (backlog take (backlogsize - 1))
      log.debug("Backlog in " + self.path.toString + " : " + backlog)
      lastData = currentData
    }

    override def preStart = {
      super.preStart()
      cancellable = context.system.scheduler.schedule(tick, tick) {
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

  class Tail(id: String, backlogSize: Int) extends Pipe {
    def selfId = id
    var messageList = List[Message]()

    def process = {
      case msg: Message => messageList = msg :: (messageList take (backlogSize - 1))
      case Query => sender ! messageList
    }
  }

  class FileDestination(id: String, fileName: String, template: String) extends Pipe {
    def selfId = id
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

  class ParserNode(id: String, field: String, parser: String, prefix: String) extends Pipe {
    def selfId = id
    val logParser = parserFactory(parser)
    def process = {
      case msg : Message => propagateMessage(msg.mergeWithPrefix(prefix, logParser(msg(field))))

    }
  }

  case class ClientConnected()

  case class ClientConnectFailed()

  case class ClientReconnectNotify()

  class TcpDestination(id: String, host: String, port: Int, template: String) extends Pipe {
    def selfId = id
    var clientActor = context.system.actorOf(Props(new ClientActor(self, host, port)))
    val msgTemplate = if (template == "") new MessageTemplate("<$PRIO> $DATE $HOST $PROGRAM $PID : $MESSAGE \n") else new MessageTemplate(template)
    var active = false;
    val buffer = new ListBuffer[Message]()
    var reconnectTimeout: Any = Nil

    def process() = {
        case m : Message => {
            log.info("Message received")
            if (active)
            {
                clientActor ! ByteString(msgTemplate.format(m))
            } else {
                buffer += m
            }
        }

        case c: ClientConnected => {
            log.info("Client connected")
            active = true;
            for (m <- buffer) {
                clientActor ! ByteString(msgTemplate.format(m))
            }
        }

        case c: ClientConnectFailed => {
            log.info("Client connect failed")
            reconnectTimeout = context.system.scheduler.scheduleOnce(FiniteDuration(1, SECONDS)) {
                this.self ! ClientReconnectNotify()
            }
        }

        case c: ClientReconnectNotify => {
            clientActor = context.system.actorOf(Props(new ClientActor(self, host, port)))
        }
    }
  }

  class ClientActor(manager: ActorRef, host: String, port: Int) extends Actor with ActorLogging with Stash {
    import context.system
    val socketAddress = new InetSocketAddress(host, port)

    IO(Tcp) ! Connect(socketAddress)
    log.info("ClientActor started") 

    def activeReceiving(connection: ActorRef) : PartialFunction[Any, Unit] = {
      case data: ByteString =>
        log.info("Sending message")
        connection ! Write(data)
      case CommandFailed(w: Write) =>
          // O/S buffer was full
        log.error("write failed")
      case "close" =>
        log.info("connection closed")
        connection ! Close
      case _: ConnectionClosed =>
        log.info("connection closed")
        context stop self
    }
   
    def receive = {
     case CommandFailed(_: Connect) =>
      println("connect failed")
      manager ! ClientConnectFailed()
      context stop self
 
     case c @ Connected(remote, local) =>
      val connection = sender()
      log.info("Connection estabilished")
      connection ! Register(self)
      manager ! ClientConnected()
      context become activeReceiving(connection)
     } 
  }

  class TcpSource(id: String, port: Int, parser: String) extends Pipe {
    def selfId = id
    val logParser : MessageParser = parserFactory(parser)
    val serverActor = context.system.actorOf(Props(new ServerActor(self, port, logParser)))
    def process = {

      case msg: Message => {
        propagateMessage(msg)
        sender ! Ack
      }
    }

    def parse(message: String) = {
      val parsedMessage = parseSyslogMessage(message)
      propagateMessage(parsedMessage)
    }

    override def pipeShutDownhook() {
      serverActor ! Terminate
    }

  }

  class ServerActor (parser: ActorRef, port: Int, msgParser: MessageParser) extends Actor {
    import context.system

    IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress("0.0.0.0", port), pullMode = true)

    def listening(listener: ActorRef): Receive = {
      case Connected(remote, local) =>
        val connection = sender()
        val handler = context.actorOf(Props(new ReceiverActor(remote, connection, parser, msgParser)))

        sender() ! Register(handler, keepOpenOnPeerClosed = true)
        listener ! ResumeAccepting(batchSize = 1)
      case Terminate =>
        listener ! Unbind
        context.stop(self)
    }

    def receive = {

      case Bound(localAddress) =>
        // Accept connections one by one
        sender() ! ResumeAccepting(batchSize = 1)
        context.become(listening(sender()))
    }

  }
  case object Ack extends Event

  class ReceiverActor(remote: InetSocketAddress, connection: ActorRef, parser: ActorRef, msgParser: MessageParser) extends Actor with ActorLogging {
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
            parser ! msgParser(message)
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
