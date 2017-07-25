import java.io.{BufferedWriter, FileOutputStream, OutputStreamWriter}
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import com.github.nscala_time.time.Imports._
import org.joda.time.format._
import akka.util.Timeout

package hyperion {

  class FileDestination(id: String, fileName: String, template: String) extends Pipe with Tickable {
    def selfId = id

    val writer = new BufferedWriter(new OutputStreamWriter(
      new FileOutputStream(fileName), "utf-8"))
    var lastMessage = DateTime.now
    implicit val timeout = Timeout(FiniteDuration(1, SECONDS))
    val msgTemplate = if (template == "") new MessageTemplate("<$PRIO> $DATE $HOST $PROGRAM $PID : $MESSAGE \n") else new MessageTemplate(template)
    var processed = 0

    override def preStart() = {
      super.preStart()
      startTicking(FiniteDuration(0, SECONDS), FiniteDuration(1, SECONDS))

    }

    override def postStop() = {
      stopTicking()
      writer.close()
      super.postStop()
    }

    def process = {
      case msg: Message => {
        writer.write(msgTemplate.format(msg))
        processed += 1
        lastMessage = DateTime.now
      }

      case Tick => {

        if (DateTime.now.minus(lastMessage.getMillis).getMillis > 1000L) {
          writer.flush()
        }

      }

      case StatsRequest => {
        sender ! StatsResponse(Map[String, Int]( "processed" -> processed))
      }
    }
  }

}