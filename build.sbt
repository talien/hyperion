import AssemblyKeys._

name := "Hyperion"
    
version := "0.1"
    
scalaVersion := "2.11.8"

resolvers += "spray repo" at "http://repo.spray.io"
    
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.4.17"
//libraryDependencies += "com.typesafe.akka" % "akka-actor_2.10" % "2.2.0"

libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.12.0"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.4.17"

libraryDependencies += "io.spray" %% "spray-can" % "1.3.3"

libraryDependencies += "io.spray" %% "spray-routing" % "1.3.3"

libraryDependencies += "io.spray" %% "spray-json" % "1.3.2"

libraryDependencies += "net.liftweb" %% "lift-json" % "2.6.2"

scalaSource in Test <<= baseDirectory(_ / "test")

assemblySettings

fork in run := true

javaOptions in run += "-XX:+UseConcMarkSweepGC" 

parallelExecution in Test := false

cancelable in Global := true
