import java.net.InetSocketAddress
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import akka.actor.{Actor, ActorLogging, Stash, ActorRef, Props, Cancellable, Terminated}
import scala.concurrent.ExecutionContext.Implicits.global
import akka.io.{IO, Tcp}
import akka.io.Tcp.{Connect, CommandFailed, Write, ConnectionClosed, Connected,
    Register, ResumeAccepting, Bound, Unbind, ResumeReading, SuspendReading, Event}
import akka.util.ByteString

package hyperion {
  case class ClientConnected()

  case class ClientConnectFailed()

  case class ClientReconnectNotify()

  case class ClientDisconnected()

  case class ClientShutdownFinished(bufferedMessages: List[Message])

  case class ClientInitiateShutdown()

  class TcpDestination(id: String, host: String, port: Int, template: String) extends Pipe {
    def selfId = id

    def createClientActor =  context.system.actorOf(Props(new ClientActor(self, host, port, msgTemplate)), id + "_client")

    val msgTemplate = if (template == "") new MessageTemplate("<$PRIO> $DATE $HOST $PROGRAM $PID : $MESSAGE \n") else new MessageTemplate(template)
    var clientActor = createClientActor
    var active = false;
    val buffer = new ListBuffer[Message]()
    var reconnectTimeout: Cancellable = null

    def initiateReconnect = {
      if (reconnectTimeout != null) {
        reconnectTimeout.cancel()
      }
      reconnectTimeout = context.system.scheduler.scheduleOnce(FiniteDuration(1, SECONDS)) {
        this.self ! ClientReconnectNotify()
        reconnectTimeout = null;
      }
    }

    def process() = {
      case m : Message => {

        if (active)
        {
          clientActor ! m
        } else {
          buffer += m
        }
      }

      case c: ClientConnected => {
        log.debug("Client connected")
        active = true;
        for (m <- buffer) {
          clientActor ! m
        }
      }

      case c: ClientConnectFailed => {
        log.debug("Client connect failed")
        initiateReconnect
      }

      case c: ClientReconnectNotify => {
        log.debug("Trying to reconnect")
        clientActor = createClientActor
      }

      case c: ClientDisconnected => {
        active = false;
        clientActor ! ClientInitiateShutdown()
      }

      case ClientShutdownFinished(messages) => {
        buffer.insertAll(0, messages)
        initiateReconnect
      }
    }
  }

  class ClientActor(manager: ActorRef, host: String, port: Int, template: MessageTemplate) extends Actor with ActorLogging with Stash {
    import context.system
    val socketAddress = new InetSocketAddress(host, port)
    var buffer = scala.collection.mutable.ListBuffer[Message]();

    IO(Tcp) ! Connect(socketAddress)
    log.info("ClientActor started")

    def shuttingDown : PartialFunction[Any, Unit] = {
      case data: Message => buffer += data

      case _: ClientInitiateShutdown =>
        manager ! ClientShutdownFinished(buffer.toList)
        context stop self
    }

    def activeReceiving(connection: ActorRef) : PartialFunction[Any, Unit] = {
      case data: Message =>
        connection ! Write(ByteString(template.format(data)))
      case CommandFailed(w: Write) =>
        // O/S buffer was full
        log.error("Write failed to connection " + socketAddress)
      case _: ConnectionClosed =>
        log.info("Connection closed")
        manager ! ClientDisconnected()
        context become shuttingDown

    }

    def receive = {
      case CommandFailed(connect) =>
        log.error("Connecting failed:" + connect.failureMessage)
        manager ! ClientConnectFailed()
        context stop self

      case c @ Connected(remote, local) =>
        val connection = sender()
        log.info("Connection estabilished to" + remote)
        connection ! Register(self)
        manager ! ClientConnected()
        context become activeReceiving(connection)
    }
  }

  class TcpSource(id: String, port: Int, parser: String) extends Pipe {
    def selfId = id
    val logParser : MessageParser = parserFactory(parser)
    val serverActor = context.system.actorOf(Props(new ServerActor(self, port, logParser)),id + "_server")
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
        val handler = context.actorOf(Props(new ReceiverActor(remote, connection, parser, msgParser)), remote.toString().replace('/','_') )

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