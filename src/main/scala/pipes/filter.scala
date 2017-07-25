package hyperion {

  class Filter(id: String, name: String, value: String) extends Pipe {
    def selfId = id
    var processed = 0
    var matched = 0

    def process = {
      case Message(data) => {
        if (data.contains(name) && data(name).matches(value)) {
          propagateMessage(Message(data))
          matched += 1
        }
        processed += 1
      }
      case StatsRequest => {
        sender ! StatsResponse(Map[String, Int]("processed" -> processed, "matched" -> matched))
      }
    }
  }

}