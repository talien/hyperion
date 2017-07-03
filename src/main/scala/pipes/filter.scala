package hyperion {

  class Filter(id: String, name: String, value: String) extends Pipe {
    def selfId = id

    def process = {
      case Message(data) =>
        if (data.contains(name) && data(name).matches(value)) propagateMessage(Message(data))
    }
  }

}