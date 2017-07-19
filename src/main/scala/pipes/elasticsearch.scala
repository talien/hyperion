import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.github.nscala_time.time.Imports.DateTime
import vc.inreach.aws.request.{AWSSigner, AWSSigningRequestInterceptor}
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.google.common.base.Supplier
import java.time.{LocalDateTime, ZoneId}
import org.apache.http.HttpHost
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback


package hyperion {

  class ElasticSearchDestination(id: String, host: String, flavour: String, template: String, config: ElasticSearchConfig) extends Pipe with Tickable {
    def selfId = id

    val client = clientBuilder()
    var messages : ListBuffer[Message] = ListBuffer[Message]();
    var lastMessage = DateTime.now
    val indexTemplate = if (template == "") new MessageTemplate("hyperion-${YEAR}-${MONTH}-${DAY}/log") else new MessageTemplate(template)

    private def createAwsSigner(region: String): AWSSigner = {
      import com.gilt.gfc.guava.GuavaConversions._

      val awsCredentialsProvider = new DefaultAWSCredentialsProviderChain
      val service = "es"
      val clock: Supplier[LocalDateTime] = () => LocalDateTime.now(ZoneId.of("UTC"))
      new AWSSigner(awsCredentialsProvider, region, service, clock)
    }

    private def createEsHttpClient(uri: String, region: String): HttpClient = {
      class AWSSignerInteceptor extends HttpClientConfigCallback {
        override def customizeHttpClient(httpClientBuilder: HttpAsyncClientBuilder): HttpAsyncClientBuilder = {
          httpClientBuilder.addInterceptorLast(new AWSSigningRequestInterceptor(createAwsSigner(region)))
        }
      }

      val hosts = ElasticsearchClientUri(uri).hosts.map {
        case (host, port) =>
          new HttpHost(host, port, "http")
      }

      log.info(s"Creating HTTP client on ${hosts.mkString(",")}")

      val client = RestClient.builder(hosts: _*)
        .setHttpClientConfigCallback(new AWSSignerInteceptor)
        .build()
      HttpClient.fromRestClient(client)
    }

    def clientBuilder() : HttpClient = {
      flavour match {
        case "http" => {
          val clientConfig = config.asInstanceOf[HTTPElasticSearchConfig]
          HttpClient(ElasticsearchClientUri(host, clientConfig.port))
        }
        case "aws" => {
          val clientConfig = config.asInstanceOf[AWSElasticSearchConfig]
          createEsHttpClient(host, clientConfig.aws_region)
        }
      }
    }

    override def preStart() {
      super.preStart();
      startTicking(FiniteDuration(1, SECONDS),FiniteDuration(1, SECONDS))
    }

    def flushMessages() = {
      if (messages.length > 0) {
        val command = bulk(messages.toSeq.map((msg) => indexInto(indexTemplate.format(msg)) fields msg.nvpairs))
        client.execute(command).await
        messages.clear()
      }
    }

    def process = {
      case msg : Message => {
        messages.append(msg)
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