import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import { api, WS_URL } from "../services/api";

export default function ChatWsPage() {
  const [tenants, setTenants] = useState([]);
  const [tenantId, setTenantId] = useState("");
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const clientRef = useRef(null);
  const sessionIdRef = useRef(crypto.randomUUID());

  useEffect(() => { api.tenantOptions().then(setTenants); }, []);

  useEffect(() => {
    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 5000,
      onConnect: () => {
        // Replies stream as {replyId, delta} messages, then {replyId, reply, done: true} with the complete text.
        client.subscribe(`/topic/replies/${sessionIdRef.current}`, (msg) => {
          const body = JSON.parse(msg.body);
          setMessages(prev => {
            const i = prev.findIndex(m => m.replyId === body.replyId && m.streaming);
            if (body.done) {
              const final = { from: "bot", replyId: body.replyId, text: body.reply, streaming: false };
              return i < 0 ? [...prev, final] : prev.map((m, j) => (j === i ? final : m));
            }
            if (typeof body.delta !== "string") return prev;
            if (i < 0) return [...prev, { from: "bot", replyId: body.replyId, text: body.delta, streaming: true }];
            return prev.map((m, j) => (j === i ? { ...m, text: m.text + body.delta } : m));
          });
        });
      }
    });
    client.activate();
    clientRef.current = client;
    return () => client.deactivate();
  }, []);

  const send = () => {
    const tenant = tenants.find(t => String(t.id) === tenantId);
    if (!input || !tenant || !clientRef.current?.connected) return;
    const payload = {
      sessionId: sessionIdRef.current,
      widgetKey: tenant.widgetKey,
      content: input
    };
    clientRef.current.publish({
      destination: "/app/chat.send",
      body: JSON.stringify(payload)
    });
    setMessages(prev => [...prev, { from: "user", text: input }]);
    setInput("");
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">Chat Test (WebSocket)</h1>
      <div className="mb-3 flex gap-2 items-center">
        <span className="font-semibold">Tenant:</span>
        <select
          className="border p-2 rounded"
          value={tenantId}
          onChange={(e) => setTenantId(e.target.value)}
        >
          <option value="">-- select --</option>
          {tenants.map(t => (
            <option key={t.id} value={t.id}>{t.name}</option>
          ))}
        </select>
      </div>
      <div className="border rounded bg-white h-64 p-3 overflow-y-auto mb-3">
        {messages.map((m, i) => (
          <div key={i} className={`mb-1 ${m.from === "user" ? "text-right" : "text-left"}`}>
            <span
              className={
                "inline-block px-2 py-1 rounded text-sm whitespace-pre-wrap " +
                (m.from === "user" ? "bg-blue-600 text-white" : "bg-gray-200") +
                (m.streaming ? " opacity-70" : "")
              }
              data-streaming={m.streaming ? "true" : undefined}
            >
              {m.text}
            </span>
          </div>
        ))}
      </div>
      <div className="flex gap-2">
        <input
          className="border p-2 rounded flex-1"
          placeholder="Type a message..."
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") send(); }}
        />
        <button className="bg-green-600 text-white px-4 py-2 rounded" onClick={send}>
          Send
        </button>
      </div>
    </div>
  );
}
