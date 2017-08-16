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

var scrollToBottom = function(textarea) {
  textarea.scrollTop(textarea[0].scrollHeight);
};

var setupClient = function(n, sessionId) {
  $("#client-" + n + "-connect").click(function() {
    var button = $(this);
    var client = new WebSocket(baseURL + "/session/" + sessionId + "?keepAlive=keepAlive&client=n" + n);

    client.onmessage = function(e) {
      var message = e.data;

      if (message != "keepAlive") {
        scrollToBottom($("#client-" + n + "-output").append(message + "\n"));
      }
      console.log("client " + n + " received message: " + message);
    };

    client.onopen = function(e) {
      client.send("Hello from client " + n + "!");
      button.attr("disabled", "disabled");
    };

    client.onclose = function(e) {
      scrollToBottom($("#client-" + n + "-output").append("connection failed\n"));
      button.removeAttr("disabled");
    };

    $("#client-" + n + "-input").enterKey(function() {
      var message = $(this).val();

      client.send(message);
      console.log("client " + n + " sent message: " + message);

      $(this).val("");
    });
  }).removeAttr("disabled");
};

var setupHost = function(n) {
  $("#host-" + n + "-start").click(function() {
    var url = baseURL + "/session?keepAlive=keepAlive";
    var info = $("#host-" + n + "-input").val();

    if (info.indexOf("/connect ") != -1 && info.indexOf(":") != -1) {
      var i = info.substring(9).split(":");
      var id = i[0];
      var secret = i[1];

      url = url + "&id=" + id + "&secret=" + secret;

      scrollToBottom($("#host-" + n + "-output").append("Trying to take over session " + id + "\n"));
    }

    var socket = new WebSocket(url);

    socket.onmessage = function(e) {
      var response = e.data;

      if (response != "keepAlive") {
        scrollToBottom($("#host-" + n + "-output").append(response + "\n"));
      }
      console.log("host " + n + " received message: " + response);

      if (response.startsWith("session:")) {
        var sessionId = response.split(":")[1].trim();

        setupClient((n - 1) * 2 + 1, sessionId);
        setupClient((n - 1) * 2 + 2, sessionId);
      }
    };

    socket.onclose = function(e) {
      scrollToBottom($("#host-" + n + "-output").append("connection failed\n"));
    };

    $("#host-" + n + "-input").enterKey(function() {
      var message = $(this).val();

      socket.send(message);
      console.log("host " + n + " sent message: " + message);

      $(this).val("");
    });
  });
};

setupHost(1);
setupHost(2);
