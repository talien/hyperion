/**
  * Created by talien on 5/23/17.
  */

import com.typesafe.config.{Config, ConfigFactory}
object createTestconfig {
  def apply() : Config = {
    ConfigFactory.parseString("""
  akka.loglevel = ERROR
  akka.actor.debug.lifecycle = off
                              """)
  }
}
