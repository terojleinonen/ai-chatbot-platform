(function () {
  let config = {};
  let stompClient = null;
  let sessionId = crypto.randomUUID();

  function createUI() {
    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = "chat-widget.css";
    document.head.appendChild(link);

    const container = document.createElement("div");
    container.id = "cw-container";

    container.innerHTML = `
        <div id="cw-bubble">💬</div>
        <div id="cw-window" style="display:none;">
            <div id="cw-header">AI Chatbot</div>
            <div id="cw-messages"></div>
            <div id="cw-input-bar">
                <input id="cw-input" placeholder="Type a message..." />
                <div id="cw-send">➤</div>
            </div>
        </div>
    `;

    document.body.appendChild(container);

    const bubble = document.getElementById("cw-bubble");
    const win = document.getElementById("cw-window");

    bubble.onclick = () => {
      win.style.display = win.style.display === "none" ? "flex" : "none";
    };

    document.getElementById("cw-send").onclick = sendMessage;
    document.getElementById("cw-input").addEventListener("keydown", (e) => {
      if (e.key === "Enter") sendMessage();
    });
  }

  function connectWs() {
    const socket = new SockJS(config.backendUrl + "/ws-chat");
    stompClient = Stomp.over(socket);

    stompClient.connect({}, () => {
      stompClient.subscribe("/topic/replies", (msg) => {
        const body = JSON.parse(msg.body);
        if (body.sessionId === sessionId) {
          addMessage(body.reply, "bot");
        }
      });
    });
  }

  function addMessage(text, from) {
    const msgBox = document.getElementById("cw-messages");
    const div = document.createElement("div");
    div.className = "cw-msg cw-" + from;
    div.innerText = text;
    msgBox.appendChild(div);
    msgBox.scrollTop = msgBox.scrollHeight;
  }

  function sendMessage() {
    const input = document.getElementById("cw-input");
    const message = input.value.trim();
    if (!message) return;
    if (!stompClient || !stompClient.connected) return;

    addMessage(message, "user");

    stompClient.publish({
      destination: "/app/chat.send",
      body: JSON.stringify({
        sessionId: sessionId,
        tenantId: config.tenantId,
        content: message
      }),
    });

    input.value = "";
  }

  window.ChatWidget = {
    init: function (cfg) {
      config = cfg;
      createUI();

      const script = document.createElement("script");
      script.src = "https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js";
      script.onload = () => {
        const stomp = document.createElement("script");
        stomp.src = "https://cdn.jsdelivr.net/npm/stompjs@2.3.3/lib/stomp.min.js";
        stomp.onload = () => connectWs();
        document.body.appendChild(stomp);
      };
      document.body.appendChild(script);
    }
  };
})();
