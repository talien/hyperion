package hyperion {
  class Tail(id: String, backlogSize: Int) extends Pipe {
    def selfId = id
    var messageList = List[Message]()

    def process = {
      case msg: Message => messageList = msg :: (messageList take (backlogSize - 1))
      case Query => sender ! messageList
    }
  }

}