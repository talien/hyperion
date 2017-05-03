import akka.util.Timeout
import scala.util.{Success, Failure}
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor._
import hyperion._
import akka.pattern.{ask, pipe}
import scala.util.Try

package hyperion {

  case class PipeOptions(name: String, typeName: String, options: Map[String, String])

  case class Create(options: NodeProperty)

  case class Join(from: String, to: String)

  case class CounterQuery(counterName: String)

  case class TailQuery(tailName: String)

  case class StatsQuery(tailName: String)

  case class StatisticItem(value: String, count: Integer)

  case class QueryConfig()

  case class UploadConfig(config: Config)

  trait HyperionPathResolver {
    val pipePrefix = "pipe_"

    def pathForPipe(name: String) = "akka://hyperion/user/" + pipePrefix + name
  }

  object PipeFactory extends HyperionPathResolver {

    def nameFromOptions(options: PipeOptions) = pipePrefix + options.name

    def construct(options: PipeOptions, system: ActorSystem): Unit = {
      options.typeName match {
        case "printer" => system.actorOf(Props(new Printer), nameFromOptions(options))
        case "source" => {
          val port: Int = options.options("port").toInt
          val parser = system.actorOf(Props(new SyslogParser), nameFromOptions(options))
          system.actorOf(Props(new ServerActor(parser, port)))
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
          val counter = system.actorSelection(pathForPipe(counterName))
          val backlogSize: Int = options.options("backlog").toInt
          val averageCounter = system.actorOf(Props(new AverageCounter(counter, 1 seconds, backlogSize)), nameFromOptions(options))
        }
        case "filter" => {
          val fieldName = options.options("fieldname")
          val matchExpression = options.options("matchexpr")
          system.actorOf(Props(new Filter(fieldName, matchExpression)), nameFromOptions(options))
        }
        case "tail" => {
          val backlogSize: Int = options.options("backlog").toInt
          system.actorOf(Props(new Tail(backlogSize)), nameFromOptions(options))
        }
        case "stats" => {
          val fieldName = options.options("fieldname")
          system.actorOf(Props(new FieldStatistics(fieldName)), nameFromOptions(options))
        }

        case "filewriter" => {
          val fileName = options.options("filename")
          val template = options.options("template")
          system.actorOf(Props(new FileDestination(fileName, template)), nameFromOptions(options))
        }
      }
    }
  }

  class PipeCreator extends Actor with HyperionPathResolver with ActorLogging{

    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))

    def create(node: NodeProperty) = {
      if (!Registry.hasNode(node.id)) {
        PipeFactory.construct(node.content, context.system)
        Registry.add(node)
      }
    }

    def join(connection: Connection) = {
      if (!Registry.hasConnection(connection)) {
        Registry.connect(connection.from, connection.to)
        val fromActor = context.system.actorSelection(pathForPipe(connection.from))
        val toActor = context.system.actorSelection(pathForPipe(connection.to))
        fromActor ! AddPipe(toActor)
      }
      else
      {
        log.info("Connection from " + connection.from + " to " + connection.to + "already exists")
      }
    }

    def validateConfig(config: Config): Unit = {
      val nodesInConnections = config.connections.foldLeft(Set[String]())((set, connection) => set + (connection.from, connection.to))
      val nodeNames = config.nodes.foldLeft(Set[String]())((set, node) => set + (node.content.name))
      val invalidNodes = nodesInConnections.filter((node) => !(nodeNames contains node) )
      if (invalidNodes.size > 0) {
        throw new Exception("Lingering connections to unexistent nodes!")
      }
    }

    def uploadConfig(config: Config) = {
      Try {
        validateConfig(config)
        config.nodes map ((node) => create(node) )
        config.connections map ((connection) => join(connection) )
        None
      }
    }

    def receive = {
      case Create(node) => create(node)
      case Join(from, to) => {
        join(Connection(from, to))
      }
      case CounterQuery(name) => {
        val counter = context.system.actorSelection(pathForPipe(name))
        val s = sender()
        log.info(pathForPipe(name));
        log.info(counter.toString());
        log.info(sender.toString());
        counter.resolveOne() onComplete {
            case Success(actorRef) => {
               log.info("Actor Found");
               log.info(sender.toString());

               val result = Await.result(actorRef ? Query, timeout.duration).asInstanceOf[Integer]
               s ! result
            }
            case Failure(e) => {
               log.info("Actor Not Found");
               log.info(sender.toString());

               s ! akka.actor.Status.Failure(e)
            }
        }
      }

      case TailQuery(name) => {
        val counter = context.system.actorSelection(pathForPipe(name))
        val result = Await.result(counter ? Query, timeout.duration).asInstanceOf[List[Message]]
        sender ! result
      }

      case StatsQuery(name) => {
        val counter = context.system.actorSelection(pathForPipe(name))
        val result = Await.result(counter ? Query, timeout.duration).asInstanceOf[Map[String, Integer]]
        sender ! result
      }
      case QueryConfig =>
        sender ! Registry.config
      case UploadConfig(config) => {
        val result = uploadConfig(config)
        sender() ! result
      }

    }

  }

}

