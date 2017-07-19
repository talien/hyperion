package hyperion {

  class Rewrite(id: String, name: String, regexp: String, value: String) extends Pipe {
    def selfId = id

    val template = new MessageTemplate(value);

    def process = {
      case msg: Message => {
        if (msg.nvpairs.contains(name)) propagateMessage(
          msg.set(name, msg.nvpairs(name).replaceAll(regexp, template.format(msg)))
        )
        else propagateMessage(msg)
      }
    }
  }

}