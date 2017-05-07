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
        fromActor ! PipeConnectionUpdate(Map((connection.to, toActor)), List())
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

    def getListOfUpdates(add: Set[Connection], remove: Set[Connection]) : Map[String, PipeConnectionUpdate] = {
      val adding = scala.collection.mutable.HashMap.empty[String,List[String]]
      add map ((connection) => {
        if (adding contains (connection.from)) {
          adding.put(connection.from, connection.to :: adding.get(connection.from).asInstanceOf[List[String]])
        } else {
          adding.put(connection.from, List(connection.to))
        }
      })
      val removing = scala.collection.mutable.HashMap.empty[String,List[String]]
      remove map ((connection) => {
        if (removing contains (connection.from)) {
          removing.put(connection.from, connection.to :: removing.get(connection.from).asInstanceOf[List[String]])
        } else {
          removing.put(connection.from, List(connection.to))
        }
      })
      println("Adding:" + adding)
      println("Removing:" + removing)
      val updates = Registry.nodes.keys map ((key : String) => {
        val addPart = if (adding contains key) adding.get(key).get map ((tokey) => tokey -> system.actorSelection(pathForPipe(tokey))) toMap else Map[String,ActorSelection]()
        val removePart = if (removing contains key) removing.get(key).get else List[String]()
        key -> PipeConnectionUpdate(
          addPart,
          removePart
        )
      }) toMap ;
      println("Updates before filter:" + updates)
      updates.filter {case(id, update) => ((update.add.size != 0) || (update.remove.size != 0))}
    }

    def updateConnections(connections: List[Connection]) = {
      val currentConnectionSet = Registry.connections.toSet
      println("Current:" + currentConnectionSet)
      val nextConnectionSet = connections.toSet
      println("Next:" + nextConnectionSet)
      val removableConnections = currentConnectionSet.diff(nextConnectionSet)
      val addingConnections = nextConnectionSet.diff(currentConnectionSet)
      println("Removable:" + removableConnections)
      val updates = getListOfUpdates(addingConnections, removableConnections)
      println("Updates:" + updates)
      updates map { case(id, update) => {
        val fromActor = system.actorSelection(pathForPipe(id))
        fromActor ! update
      }}
      addingConnections map { (connection) => {
        Registry.connect(connection.from, connection.to)
      }}
    }

    def uploadConfig(config: Config) = {
      Try {
        println("Config:" + config)
        validateConfig(config)
        config.nodes map ((node) => create(node) )
        updateConnections(config.connections)
        None
      }
    }

    def queryPipe[T](name: String) = {
      val pipe = context.system.actorSelection(pathForPipe(name))
      val s = sender
      pipe.resolveOne() onComplete {
        case Success(actorRef) => {
          log.info("Actor Found");
          log.info(sender.toString());

          val result = Await.result(actorRef ? Query, timeout.duration).asInstanceOf[T]
          s ! result
        }
        case Failure(e) => {
          log.info("Actor Not Found");
          log.info(sender.toString());

          s ! akka.actor.Status.Failure(e)
        }
      }
    }

    def receive = {
      case Create(node) => create(node)
      case Join(from, to) => {
        join(Connection(from, to))
      }
      case CounterQuery(name) => {
        queryPipe[Integer](name)
      }

      case TailQuery(name) => {
        queryPipe[List[Message]](name)
      }

      case StatsQuery(name) => {
        queryPipe[Map[String, Integer]](name)
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

