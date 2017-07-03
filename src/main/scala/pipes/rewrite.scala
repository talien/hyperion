package hyperion {

  class Rewrite(id: String, name: String, regexp: String, value: String) extends Pipe {
    def selfId = id

    def process = {
      case Message(data) =>
        if (data.contains(name)) propagateMessage(
          Message(data.updated(name, data(name) replaceAll(regexp, value)))
        )
        else propagateMessage(Message(data))
    }
  }

}