import AssemblyKeys._

name := "Hyperion"
    
version := "0.0.1-alpha1"
    
scalaVersion := "2.12.3"

resolvers += "spray repo" at "http://repo.spray.io"
    
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.3"

libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.16.0"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.5.3"

libraryDependencies += "net.liftweb" %% "lift-json" % "3.1.0"

libraryDependencies += "com.sksamuel.elastic4s" %% "elastic4s-core" % "5.4.5"

libraryDependencies += "com.sksamuel.elastic4s" %% "elastic4s-http" % "5.4.5"

libraryDependencies += "vc.inreach.aws" % "aws-signing-request-interceptor" % "0.0.16"

libraryDependencies += "com.gilt" %% "gfc-guava" % "0.2.5"

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.5"

libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.6.4"

libraryDependencies += "io.kamon" %% "kamon-core" % "0.6.6"

libraryDependencies += "io.kamon" %% "kamon-akka-2.4" % "0.6.6"

libraryDependencies += "io.kamon" %% "kamon-jmx" % "0.6.6"

libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.9" 

libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.9"

scalaSource in Test <<= baseDirectory(_ / "test")

assemblySettings

fork in run := true

javaOptions in run += "-XX:+UseConcMarkSweepGC" 

parallelExecution in Test := false

cancelable in Global := true
