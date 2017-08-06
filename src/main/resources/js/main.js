$(document).ready(function() {
  var socket = new WebSocket(baseURL + "/session?keepAlive=keepAlive");

  socket.onmessage = function(e) {
    var response = e.data;

    if (response != "keepAlive") {
      $("#host-output").append(response + "</br>");
    }
    console.log("host received message: " + response);

    if (response.startsWith("session:")) {
      var sessionId = response.split(":")[1].trim();

      $("#client-1-connect").click(function() {
        var client = new WebSocket(baseURL + "/session/" + sessionId + "?keepAlive=keepAlive&client=n1");

        client.onmessage = function(e) {
          var message = e.data;

          if (message != "keepAlive") {
            $("#client-1-output").append(message + "</br>");
          }
          console.log("client 1 received message: " + message);
        };

        client.onopen = function(e) {
          client.send("ClientId: 0x1000");
        };

        client.onclose = function(e) {
          $("#client-1-output").append("connection failed");
        };

        $("#client-1-input").change(function() {
          var message = $(this).val();

          client.send(message);
          console.log("client 1 sent message: " + message);

          $(this).val("");
        });
      });

      $("#client-2-connect").click(function() {
        var client2 = new WebSocket(baseURL + "/session/" + sessionId + "?keepAlive=keepAlive&client=n2");

        client2.onmessage = function(e) {
          var message = e.data;

          if (message != "keepAlive") {
            $("#client-2-output").append(message + "</br>");
          }
          console.log("client 2 received message: " + message);
        };

        client2.onopen = function(e) {
          client2.send("ClientId: 0x1001");
        };

        client2.onclose = function(e) {
          $("#client-2-output").append("connection failed");
        };

        $("#client-2-input").change(function() {
          var message = $(this).val();

          client2.send(message);
          console.log("client 2 sent message: " + message);

          $(this).val("");
        });
      });
    }
  };

  socket.onclose = function(e) {
    $("#host-output").append("connection failed");
  };

  $("#host-input").change(function() {
    var message = $(this).val();

    socket.send(message);
    console.log("host sent message: " + message);
  });
});
