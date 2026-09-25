export default function Dashboard() {
  return (
    <div>
      <h1 className="text-2xl font-bold mb-2">Dashboard</h1>
      <p className="text-gray-700 mb-4">
        Welcome to your AI Chatbot admin panel. Use the sidebar to manage tenants,
        FAQs, retrain the AI model and test the chat.
      </p>
      <ul className="list-disc list-inside text-gray-700">
        <li><b>Tenants</b> – create and manage clients.</li>
        <li><b>FAQs</b> – define what the bot can answer.</li>
        <li><b>Chat (WebSocket)</b> – test real-time bot responses.</li>
        <li><b>Import / Export</b> – bulk manage FAQ data.</li>
        <li><b>Users</b> – manage admin accounts and change your password.</li>
      </ul>
    </div>
  );
}
