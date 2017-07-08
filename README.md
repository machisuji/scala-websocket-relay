# Scala WebSocket Relay

Connects a host with clients through web sockets when the clients cannot connect to the host
directly due to firewall restrictions. In this case the clients instead connect to this relay server.

## Build

Built using Scala `2.12.2` with [SBT](http://www.scala-sbt.org/download.html) `0.13.5`.

## Development

Run using `sbt run` and go to [http://localhost:8080/](http://localhost:8080/) to see
the documentation and play around with the relay.
