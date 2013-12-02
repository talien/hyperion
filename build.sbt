import AssemblyKeys._

name := "Hyperion"
    
version := "0.1"
    
scalaVersion := "2.10.2"

resolvers += "spray repo" at "http://repo.spray.io"
    
libraryDependencies += "com.typesafe.akka" % "akka-actor_2.10.0-RC1" % "2.1.0-RC1"
//libraryDependencies += "com.typesafe.akka" % "akka-actor_2.10" % "2.2.0"

libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "0.6.0"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.0" % "test"

libraryDependencies += "com.typesafe.akka" % "akka-testkit_2.10.0-RC1" % "2.1.0-RC1" % "test"

libraryDependencies += "io.spray" % "spray-can" % "1.1.0"

libraryDependencies += "io.spray" % "spray-routing" % "1.1.0"

libraryDependencies += "io.spray" % "spray-json_2.10" % "1.2.3"

scalaSource in Test <<= baseDirectory(_ / "test")

assemblySettings

