name := "scala-websocket-relay"

version := "0.7.0"

scalaVersion := "2.12.2"

scalacOptions := Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.9"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.1"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.5.3" % "test"
