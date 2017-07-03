import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import akka.actor.{Actor, ActorLogging, ActorSelection}
import akka.util.Timeout
import akka.pattern.{ask, pipe}

package hyperion {

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

  class MessageCounter(id: String) extends Counter {
    def selfId = id

    def count(data: Map[String, String]) = {
      counter = counter + 1
    }
  }

  class FieldValueCounter(id: String, name: String, value: String) extends Counter {
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

  class AverageCounter(id: String, counter: ActorSelection, tick: FiniteDuration, backlogsize: Int) extends Actor with ActorLogging with Tickable {
    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
    def selfId = id
    var backlog = List[Int]()
    var lastData = 0

    def updateBacklog = {
      log.debug("Updating backlog in " + self.path.toString)
      val currentData = Await.result(counter ? Query, timeout.duration).asInstanceOf[Integer]
      backlog = (currentData - lastData) :: (backlog take (backlogsize - 1))
      log.debug("Backlog in " + self.path.toString + " : " + backlog)
      lastData = currentData
      println("Ticked")
    }

    override def preStart = {
      super.preStart()
      startTicking(tick, tick);
    }

    def countAverage = if (backlog.size != 0) (backlog.sum / backlog.size) else 0

    def receive = {
      case Query => sender ! countAverage
      case Tick => updateBacklog
    }

    override def postStop = stopTicking
  }

}