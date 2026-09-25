import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";

export default function LoginPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");
    let ok = false;
    try {
      ok = await login(username, password);
    } catch {
      setError("Cannot reach the backend");
      return;
    }
    if (!ok) setError("Invalid username or password");
    else navigate("/dashboard");
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-900">
      <form
        onSubmit={handleSubmit}
        className="bg-white rounded shadow-lg p-8 w-full max-w-sm"
      >
        <h1 className="text-xl font-bold mb-4 text-center">Admin Login</h1>
        <input
          className="border p-2 w-full mb-2 rounded"
          placeholder="Username"
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <input
          type="password"
          autoComplete="current-password"
          className="border p-2 w-full mb-2 rounded"
          placeholder="Enter admin password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {error && <div className="text-red-600 text-sm mb-2">{error}</div>}
        <button className="w-full bg-slate-900 text-white py-2 rounded hover:bg-slate-700">
          Login
        </button>
      </form>
    </div>
  );
}
