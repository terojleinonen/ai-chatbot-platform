(function () {
  let config = {};
  let stompClient = null;
  // Bot replies still being streamed, by reply id.
  const streaming = {};
  // Messages sent whose reply hasn't started arriving yet; the typing indicator shows while this is above 0.
  let awaitingReplies = 0;
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
            <div id="cw-messages">
                <div id="cw-typing" class="cw-msg cw-typing" role="status" hidden>
                    <span class="cw-dot"></span><span class="cw-dot"></span><span class="cw-dot"></span>
                    <span class="cw-sr-only">The assistant is typing</span>
                </div>
            </div>
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
      // A reply arrives as {replyId, delta} messages (text to append as the AI writes it), then
      // {replyId, reply, done: true} with the complete text, which replaces what was streamed.
      stompClient.subscribe("/topic/replies/" + sessionId, (msg) => {
        const body = JSON.parse(msg.body);
        // The first message of a reply means the assistant has started answering.
        if (!streaming[body.replyId] && (body.done || typeof body.delta === "string")) replyStarted();
        if (body.done) {
          const div = streaming[body.replyId] || addMessage("", "bot");
          delete streaming[body.replyId];
          div.textContent = body.reply;
          div.classList.remove("cw-streaming");
          scrollToEnd();
        } else if (typeof body.delta === "string") {
          let div = streaming[body.replyId];
          if (!div) {
            div = streaming[body.replyId] = addMessage("", "bot");
            div.classList.add("cw-streaming");
          }
          div.textContent += body.delta;
          scrollToEnd();
        }
      });
    }, () => {
      // Connection lost: replies in progress won't arrive, so stop waiting for them; retry after a short delay.
      awaitingReplies = 0;
      updateTyping();
      setTimeout(connectWs, 5000);
    });
  }

  function addMessage(text, from) {
    const msgBox = document.getElementById("cw-messages");
    const div = document.createElement("div");
    div.className = "cw-msg cw-" + from;
    div.textContent = text;
    // Keep the typing indicator below the conversation.
    msgBox.insertBefore(div, document.getElementById("cw-typing"));
    scrollToEnd();
    return div;
  }

  function replyStarted() {
    awaitingReplies = Math.max(0, awaitingReplies - 1);
    updateTyping();
  }

  function updateTyping() {
    document.getElementById("cw-typing").hidden = awaitingReplies === 0;
    scrollToEnd();
  }

  function scrollToEnd() {
    const msgBox = document.getElementById("cw-messages");
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
      widgetKey: config.widgetKey,
      content: message
    }));

    input.value = "";
    awaitingReplies++;
    updateTyping();
  }

  window.ChatWidget = {
    /**
     * cfg.backendUrl  - base URL of the backend, e.g. "http://localhost:8080" (required)
     * cfg.widgetKey   - the tenant's widget key from the admin panel's Tenants page (required)
     * cfg.title       - header text (optional)
     * cfg.cssUrl      - custom stylesheet URL (optional)
     */
    init: function (cfg) {
      config = cfg || {};
      if (!config.backendUrl || !config.widgetKey) {
        console.error("ChatWidget.init requires backendUrl and widgetKey");
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
