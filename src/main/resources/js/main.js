$.fn.enterKey = function (fnc) {
    return this.each(function () {
        $(this).keypress(function (ev) {
            var keycode = (ev.keyCode ? ev.keyCode : ev.which);
            if (keycode == '13') {
                fnc.call(this, ev);
            }
        })
    })
}

$("#host-start").click(function() {
  var url = baseURL + "/session?keepAlive=keepAlive";
  var info = $("#host-input").val();

  if (info.indexOf("/connect ") != -1 && info.indexOf(":") != -1) {
    var i = info.substring(9).split(":");
    var id = i[0];
    var secret = i[1];

    url = url + "&id=" + id + "&secret=" + secret;

    alert("Trying to take over session " + id);
  }

  var socket = new WebSocket(url);
  var scrollToBottom = function() {
    $("html, body").animate({ scrollTop: $(document).height() }, "slow");
  };

  socket.onmessage = function(e) {
    var response = e.data;

    if (response != "keepAlive") {
      $("#host-output").append(response + "</br>");
      scrollToBottom();
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
            scrollToBottom();
          }
          console.log("client 1 received message: " + message);
        };

        client.onopen = function(e) {
          client.send("ClientId: 0x1000");
        };

        client.onclose = function(e) {
          $("#client-1-output").append("connection failed");
          scrollToBottom();
        };

        $("#client-1-input").enterKey(function() {
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
            scrollToBottom();
          }
          console.log("client 2 received message: " + message);
        };

        client2.onopen = function(e) {
          client2.send("ClientId: 0x1001");
        };

        client2.onclose = function(e) {
          $("#client-2-output").append("connection failed");
          scrollToBottom();
        };

        $("#client-2-input").enterKey(function() {
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

  $("#host-input").enterKey(function() {
    var message = $(this).val();

    socket.send(message);
    console.log("host sent message: " + message);

    $(this).val("");
  });
});
