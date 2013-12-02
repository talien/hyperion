import akka.util.{ ByteString, ByteStringBuilder }
import java.net.InetSocketAddress
import akka.actor._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.{ ask, pipe }

package hyperion {
    case class LogLine(line: String)
	case class Terminate
	case class AddActor(actor : ActorRef)
	case class Query
	case class Reset
	case class Tick
	
	abstract class Pipe extends Actor 
	{
	   var next = List[ActorRef]();
	   
	   def receiveControl : PartialFunction[Any, Unit] = {
	     case AddActor(nextActor) =>
	       next = nextActor :: next
	   }
	   
	   def receive = receiveControl orElse process
	   
	   def process : PartialFunction[Any, Unit]
	   
	   def propagateMessage(message: LogMessage) =
	     if (!next.isEmpty)
	     {
	    	 next.foreach( actor =>
	    	    actor ! message
	    	 )
	     }
	}
	
	class Printer extends Pipe
	{
	   def process = 
	   {
	     case LogMessage(data) => println(data("MESSAGE"))
	   }
	}
	
	class Filter(name: String, value:String) extends Pipe
	{
	  def process =
	  {
	    case LogMessage(data) =>
	      if (data.contains(name) && data(name).matches(value)) propagateMessage(LogMessage(data))
	  }
	}
	
	class Rewrite(name: String, regexp: String, value: String) extends Pipe
	{
	  def process =
	  {
	    case LogMessage(data) =>
	      if (data.contains(name)) propagateMessage(
	        LogMessage(data.updated(name, data(name) replaceAll(regexp, value) ) )
	      ) else propagateMessage(LogMessage(data))
	  }
	}
	
	abstract class Counter extends Pipe
	{
	  var counter = 0
	  def process = {
	    case LogMessage(data) =>
	      count(data)
	    case Reset =>
	      counter = 0
	    case Query =>
	    {
	      sender ! counter
	    }
	  }
	  
	  def count(data: Map[String,String])
	}
	
	class MessageCounter extends Counter
	{
	  def count(data: Map[String,String]) = { counter = counter + 1 }
	}
	
	class FieldValueCounter(name : String, value: String) extends Counter
	{
	  def count(data: Map[String,String]) = {
	    if (data.contains(name) && data(name).matches(value)) counter = counter + 1
	  }
	}
	
	class AverageCounter(counter: ActorRef, tick: FiniteDuration, backlogsize: Int) extends Actor
	{
	  implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
	  var backlog = List[Int]() 
	  var cancellable : Any = Nil
	  var lastData = 0
	  
	  def updateBacklog = 
	  {
	    val currentData = Await.result(counter ? Query, timeout.duration).asInstanceOf[Integer]
	    backlog = (currentData - lastData) :: (backlog take (backlogsize - 1))
	    lastData = currentData
	  }
	  
	  override def preStart = {
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
	
	class Parser extends Pipe
	{
	
	   def process = 
	   {
	     case LogLine(line) =>
	       parse(line)	      
	   }
	
	   def parse(message: String) =
	   {
	     val data = parseMessage(message)
	     propagateMessage(LogMessage(data))
	   }
	
	}
	
	class ReceiverActor(parser: ActorRef) extends Actor
	{
	
	   val state = IO.IterateeRef.Map.async[IO.Handle]()(context.dispatcher)
	   val LF = ByteString("\n")
	   
	   override def preStart {
	        IOManager(context.system) listen new InetSocketAddress(1514)
	   }
	
	   def receive = 
	   {
	     case IO.NewClient(server) =>
	     {
	       val socket = server.accept()
	       println("Accepted socket")
	       state(socket) flatMap ( _ => this.process )
	     }
	     case IO.Read(socket, bytes) =>
	     {
	       state(socket)(IO.Chunk(bytes))
	     }
	
	     case IO.Closed(socket, reason) =>
	     {
	       state -= socket
	       println("Socket closed")
	     }
	   }
	
	   def process : IO.Iteratee[Unit] =
	   {
	      IO.repeat {
	        for {
	          logLine <- readLine
	        }
	        yield { 
	          parser ! LogLine(logLine)
	        }
	      }
	   }
	
	   def readLine : IO.Iteratee[String] =
	   {
	      for {
	          line <- IO takeUntil LF
	      } yield line.decodeString("UTF-8")
	
	   }
	}
}