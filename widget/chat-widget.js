(function () {
  let config = {};
  let stompClient = null;
  let sessionId = (window.crypto && crypto.randomUUID)
    ? crypto.randomUUID()
    : Date.now().toString(36) + Math.random().toString(36).slice(2);
  // Resolve chat-widget.css next to this script, not relative to the host page.
  const scriptSrc = document.currentScript ? document.currentScript.src : "";
  const cssUrl = scriptSrc ? new URL("chat-widget.css", scriptSrc).href : "chat-widget.css";

  function createUI() {
    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = config.cssUrl || cssUrl;
    document.head.appendChild(link);

    const container = document.createElement("div");
    container.id = "cw-container";

    container.innerHTML = `
        <div id="cw-bubble">💬</div>
        <div id="cw-window" style="display:none;">
            <div id="cw-header"></div>
            <div id="cw-messages"></div>
            <div id="cw-input-bar">
                <input id="cw-input" placeholder="Type a message..." />
                <div id="cw-send">➤</div>
            </div>
        </div>
    `;

    document.body.appendChild(container);
    document.getElementById("cw-header").innerText = config.title || "AI Chatbot";

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
    stompClient.debug = null;

    stompClient.connect({}, () => {
      stompClient.subscribe("/topic/replies/" + sessionId, (msg) => {
        const body = JSON.parse(msg.body);
        addMessage(body.reply, "bot");
      });
    }, () => {
      // Connection lost: retry after a short delay.
      setTimeout(connectWs, 5000);
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

    // stompjs 2.x API: send(destination, headers, body)
    stompClient.send("/app/chat.send", {}, JSON.stringify({
      sessionId: sessionId,
      tenantId: Number(config.tenantId),
      content: message
    }));

    input.value = "";
  }

  window.ChatWidget = {
    /**
     * cfg.backendUrl  - base URL of the backend, e.g. "http://localhost:8080" (required)
     * cfg.tenantId    - tenant ID from the admin panel (required)
     * cfg.title       - header text (optional)
     * cfg.cssUrl      - custom stylesheet URL (optional)
     */
    init: function (cfg) {
      config = cfg || {};
      if (!config.backendUrl || !config.tenantId) {
        console.error("ChatWidget.init requires backendUrl and tenantId");
        return;
      }
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
