name := "scala-websocket-relay"

version := "0.7.1"

scalaVersion := "2.12.2"

scalacOptions := Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.9"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.1"

libraryDependencies += "org.eclipse.jetty.websocket" % "websocket-client" % "9.4.7.v20170914"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.5.3" % "test"
