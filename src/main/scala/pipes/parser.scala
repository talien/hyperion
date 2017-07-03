package hyperion {
  class ParserNode(id: String, field: String, parser: String, prefix: String) extends Pipe {
    def selfId = id
    val logParser = parserFactory(parser)
    def process = {
      case msg : Message => propagateMessage(msg.mergeWithPrefix(prefix, logParser(msg(field))))

    }
  }
}