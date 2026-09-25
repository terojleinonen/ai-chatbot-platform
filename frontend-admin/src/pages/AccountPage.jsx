import { useState } from "react";
import { api } from "../services/api";
import { useAuth } from "../auth/AuthContext.jsx";

const ROLE_LABELS = { SUPER_ADMIN: "Super admin", TENANT_ADMIN: "Tenant admin" };

export default function AccountPage() {
  const { username, role, startSession } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const changePassword = async (e) => {
    e.preventDefault();
    setError("");
    setNotice("");
    if (next !== confirm) {
      setError("New passwords do not match.");
      return;
    }
    try {
      startSession(await api.changeMyPassword(current, next));
      setNotice("Your password was changed. Other sessions were signed out.");
      setCurrent("");
      setNext("");
      setConfirm("");
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div className="max-w-md">
      <h1 className="text-2xl font-bold mb-4">My account</h1>
      <p className="text-gray-700 mb-4">
        Signed in as <b>{username}</b>{role && <> ({ROLE_LABELS[role] || role})</>}.
      </p>

      {error && <div className="mb-4 p-3 rounded bg-red-100 text-red-800" role="alert">{error}</div>}
      {notice && <div className="mb-4 p-3 rounded bg-green-100 text-green-800" role="status">{notice}</div>}

      <form onSubmit={changePassword} className="bg-white p-4 rounded shadow">
        <h2 className="font-semibold mb-2">Change my password</h2>
        <input
          type="password"
          className="border p-2 w-full mb-2 rounded"
          placeholder="Current password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
        />
        <input
          type="password"
          className="border p-2 w-full mb-2 rounded"
          placeholder="New password"
          autoComplete="new-password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
        />
        <input
          type="password"
          className="border p-2 w-full mb-1 rounded"
          placeholder="Confirm new password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
        <p className="text-xs text-gray-500 mb-2">At least 12 characters.</p>
        <button className="bg-slate-900 text-white px-4 py-2 rounded">Change password</button>
      </form>
    </div>
  );
}
