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
    def actorSystemName : String

    def pathForPipe(name: String) = "akka://" + actorSystemName + "/user/" + pipePrefix + name
  }

  class PipeFactory(actorSystemNameParam : String) extends HyperionPathResolver {

    def actorSystemName = actorSystemNameParam

    def nameFromOptions(options: PipeOptions) = pipePrefix + options.name

    def nameFromId(id: String) = pipePrefix + id

    def construct(id: String, options: PipeOptions, system: ActorSystem): Unit = {
      options.typeName match {
        case "printer" => system.actorOf(Props(new Printer), nameFromId(id))
        case "source" => {
          val port: Int = options.options("port").toInt
          val parser = system.actorOf(Props(new SyslogParser), nameFromId(id))
          system.actorOf(Props(new ServerActor(parser, port)))
        }
        case "counter" => system.actorOf(Props(new MessageCounter), nameFromId(id))
        case "rewrite" => {
          val fieldName = options.options("fieldname")
          val matchExpression = options.options("matchexpr")
          val substitutionValue = options.options("substvalue")
          system.actorOf(Props(new Rewrite(fieldName, matchExpression, substitutionValue)), nameFromId(id))
        }
        case "averageCounter" => {
          val counterName = options.options("counter")
          val counter = system.actorSelection(pathForPipe(counterName))
          val backlogSize: Int = options.options("backlog").toInt
          val averageCounter = system.actorOf(Props(new AverageCounter(counter, 1 seconds, backlogSize)), nameFromId(id))
        }
        case "filter" => {
          val fieldName = options.options("fieldname")
          val matchExpression = options.options("matchexpr")
          system.actorOf(Props(new Filter(fieldName, matchExpression)), nameFromId(id))
        }
        case "tail" => {
          val backlogSize: Int = options.options("backlog").toInt
          system.actorOf(Props(new Tail(backlogSize)), nameFromId(id))
        }
        case "stats" => {
          val fieldName = options.options("fieldname")
          system.actorOf(Props(new FieldStatistics(fieldName)), nameFromId(id))
        }

        case "filewriter" => {
          val fileName = options.options("filename")
          val template = options.options("template")
          system.actorOf(Props(new FileDestination(fileName, template)), nameFromId(id))
        }
      }
    }
  }

  class PipeCreator(system: ActorSystem, pipeFactory: PipeFactory) extends Actor with HyperionPathResolver with ActorLogging{

    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))

    def actorSystemName = pipeFactory.actorSystemName

    def create(node: NodeProperty) = {
      log.debug("Creating node:" + node.id)
      if (!Registry.hasNode(node.id)) {
        pipeFactory.construct(node.id, node.content, system)
        Registry.add(node)
      }
    }

    def join(connection: Connection) = {
      if (!Registry.hasConnection(connection)) {
        Registry.connect(connection.from, connection.to)
        val fromActor = system.actorSelection(pathForPipe(connection.from))
        val toActor = system.actorSelection(pathForPipe(connection.to))
        fromActor ! AddPipe(toActor)
      }
      else
      {
        log.debug("Connection from " + connection.from + " to " + connection.to + "already exists")
      }
    }

    def validateConfig(config: Config): Unit = {
      val nodesInConnections = config.connections.foldLeft(Set[String]())((set, connection) => set + (connection.from, connection.to))
      log.debug("Name of nodes in connections:" + nodesInConnections)
      val nodeNames = config.nodes.foldLeft(Set[String]())((set, node) => set + (node.id))
      log.debug("Name of all nodes:" + nodeNames)
      val invalidNodes = nodesInConnections.filter((node) => !(nodeNames contains node) )
      if (invalidNodes.size > 0) {
        log.info("Invalid nodes:" + invalidNodes)
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
        val s = sender
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
        log.debug("Querying tail:" + pathForPipe(name))
        val tail = system.actorSelection(pathForPipe(name))
        val result = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
        sender ! result
      }

      case StatsQuery(name) => {
        val counter = system.actorSelection(pathForPipe(name))
        val result = Await.result(counter ? Query, timeout.duration).asInstanceOf[Map[String, Integer]]
        sender ! result
      }
      case QueryConfig =>
        sender ! Registry.config
      case UploadConfig(config) => {
        val result = uploadConfig(config)
        sender ! result
      }

    }

  }

}

