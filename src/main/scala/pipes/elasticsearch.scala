import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.github.nscala_time.time.Imports.DateTime

package hyperion {

  class ElasticSearchDestination(id: String, host: String, port: Int) extends Pipe with Tickable {
    def selfId = id
    val client = HttpClient(ElasticsearchClientUri(host, port))
    var messages : ListBuffer[Message] = ListBuffer[Message]();
    var lastMessage = DateTime.now

    override def preStart() {
      super.preStart();
      startTicking(FiniteDuration(1, SECONDS),FiniteDuration(1, SECONDS))
    }

    def flushMessages() = {
      val command = bulk(messages.toSeq.map(indexInto("logstash-2017-06-26/log") fields _.nvpairs ))
      client.execute(command).await
      messages.clear()
    }

    def process = {
      case msg : Message => {
        //val command = indexInto("logstash-2017-06-26/log").fields(msg.nvpairs)
        messages.append(msg);
        if (messages.length >= 100) {
          flushMessages()
        }

      }

      case Tick => {

        if (DateTime.now.minus(lastMessage.getMillis).getMillis > 1000L) {
          flushMessages()
        }

      }
    }

    override def postStop() = {
      stopTicking()
      super.postStop()
    }
  }
}