import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor._
import hyperion._
import akka.pattern.{ ask, pipe }

package hyperion {

	case class PipeOptions(name: String, typeName: String, options: Map[String, String])
	case class Create(options: PipeOptions)
	case class Join(from : String, to :String)
	case class CounterQuery(counterName: String)
	case class TailQuery(tailName : String)
	
	trait HyperionPathResolver {
	  val pipePrefix = "pipe_"
	  
	  def pathForPipe(name : String) = "akka://hyperion/user/" + pipePrefix + name
	}
	
	object PipeFactory extends HyperionPathResolver {
	  
	   def nameFromOptions(options : PipeOptions) = pipePrefix + options.name
	   
	   def construct(options: PipeOptions, system : ActorSystem) : Unit = {
	     options.typeName match {
	       case "printer" => system.actorOf(Props(new Printer), nameFromOptions(options))
	       case "source" => { 
	           val parser = system.actorOf(Props(new Parser), nameFromOptions(options))
	           system.actorOf(Props(new ReceiverActor(parser)))
	       }
	       case "counter" => system.actorOf(Props(new MessageCounter), nameFromOptions(options))
	       case "rewrite" => {
	    	   val fieldName = options.options("fieldname")
	    	   val matchExpression = options.options("matchexpr")
	    	   val substitutionValue = options.options("substvalue")
	    	   system.actorOf(Props(new Rewrite(fieldName, matchExpression, substitutionValue)), nameFromOptions(options))
	       }
	       case "averageCounter" => {
	           val counterName = options.options("counter")
	           val counter = system.actorFor(pathForPipe(counterName))
	           val backlogSize : Int = options.options("backlog").toInt
	           val averageCounter = system.actorOf(Props(new AverageCounter(counter, 1 seconds, backlogSize)), nameFromOptions(options))
	       }
	       case "filter" => {
	           val fieldName = options.options("fieldname")
	           val matchExpression = options.options("matchexpr")
	           system.actorOf(Props(new Filter(fieldName, matchExpression)), nameFromOptions(options))
	       }
	       case "tail" => {
	           val backlogSize : Int = options.options("backlog").toInt
	           system.actorOf(Props(new Tail(backlogSize)), nameFromOptions(options))
	       }
	         
	     } 
	   }
	}
	
	class PipeCreator extends Actor with HyperionPathResolver {
	   
	   implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
	  
	   def receive = {
	     case Create(options) => PipeFactory.construct(options, context.system)
	     case Join(from, to) =>
	       {
	         val fromActor = context.system.actorFor(pathForPipe(from))
	         val toActor = context.system.actorFor(pathForPipe(to))
	         fromActor ! AddActor(toActor)
	       }
	     case CounterQuery(name) =>
	       {
	         val counter = context.system.actorFor(pathForPipe(name))
	         val result = Await.result(counter ? Query, timeout.duration).asInstanceOf[Integer]
	         sender ! result
	       }
	       
	     case TailQuery(name) =>
	       {
	         val counter = context.system.actorFor(pathForPipe(name))
	         val result = Await.result(counter ? Query, timeout.duration).asInstanceOf[List[Message]]
	         sender ! result
	       }
	   }
	}
	
}

