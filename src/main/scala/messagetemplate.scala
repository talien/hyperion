/**
  * Created by talien on 4/29/17.
  */
import com.github.nscala_time.time.Imports._
import org.joda.time.format._

package hyperion {

  class TemplateItem()
  case class Literal(literal: String) extends TemplateItem
  case class Variable(variable: String) extends TemplateItem
  case class DateMacro() extends TemplateItem {
    def process(msg: Message) : String = {
      val epoch = msg.nvpairs("DATE") toLong
      val date = new DateTime(epoch)
      val fmt = ISODateTimeFormat.dateTime()
      return fmt.print(date)
    }
  }

  class MessageTemplate(template: String) {
    val templateItems: scala.collection.mutable.MutableList[TemplateItem] = scala.collection.mutable.MutableList[TemplateItem]();
    parse(template)

    def parse(template: String): Unit = {
      if (template.length == 0) return
      val variable = template.indexOf("$")
      if ((variable < template.length()) && (template.charAt(variable + 1) == '$')) {
        templateItems += Literal("$")
        parse(template.substring(variable + 2))
        return
      }
      if (variable > 0) {
        templateItems += Literal(template.substring(0, variable))
      }
      if (variable != -1) {
        val space = template.indexOf(" ", variable)
        var variableName = if (space != -1) template.substring(variable + 1, space) else template.substring(variable + 1)
        if (variableName == "DATE"){
          templateItems += DateMacro()
        } else{
          templateItems += Variable(variableName)
        }

        if (space != -1)
          {
            parse(template.substring(space))
          }
      }
      else {
        templateItems += Literal(template)
      }
    }

    def format(message: Message) = {
      templateItems.foldLeft("")( (x, templateItem) => {
         templateItem match {
            case Literal(literal) => x + literal
            case Variable(variable) => x + message.nvpairs.getOrElse(variable, "")
            case d : DateMacro => x + d.process(message)
          }
        }
      )
    }
  }

}
