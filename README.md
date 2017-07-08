# Scala WebSocket Relay

Connects a host with clients through web sockets when the clients cannot connect to the host
directly due to firewall restrictions. In this case the clients instead connect to this relay server.

## Requirements

* Java `8`
* Scala `2.12.2`

## Build

Built using [SBT](http://www.scala-sbt.org/download.html) `0.13.5`.

Run `sbt assembly` to generate an exutable JAR under `target/scala-2.12/`.

## Development

Run using `sbt run` and go to [http://localhost:8080/](http://localhost:8080/) to see
the documentation and play around with the relay.
