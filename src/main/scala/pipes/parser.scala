import scala.util.{Try, Success, Failure}

package hyperion {
  class ParserNode(id: String, field: String, parser: String, prefix: String) extends Pipe {
    def selfId = id
    val logParser = parserFactory(parser)
    var processed = 0
    var failed = 0
    def process = {
      case msg : Message => {
        Try {
          logParser(msg(field))
        } match {
          case Success(t) => propagateMessage(msg.mergeWithPrefix(prefix, t))
          case Failure(e) => {
            failed += 1
          }

        }
        processed += 1
      }

      case StatsRequest => {
        sender ! StatsResponse(Map[String, Int]("processed" -> processed, "parse_failed" -> failed))
      }

    }
  }
}