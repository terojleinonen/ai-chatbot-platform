import { useEffect, useState } from "react";
import { api } from "../services/api";
import { useAuth } from "../auth/AuthContext.jsx";

const MIN_PASSWORD = 12;

export default function UsersPage() {
  const { username: me, startSession } = useAuth();
  const [users, setUsers] = useState([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const [newName, setNewName] = useState("");
  const [newPassword, setNewPassword] = useState("");

  const [resetting, setResetting] = useState(null);
  const [resetPassword, setResetPassword] = useState("");

  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");

  const load = () => api.listUsers().then(setUsers).catch(e => setError(e.message));

  useEffect(() => { load(); }, []);

  // Runs an action, showing its error or success message.
  const run = async (action, success) => {
    setError("");
    setNotice("");
    try {
      await action();
      setNotice(success);
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    }
  };

  const createUser = async (e) => {
    e.preventDefault();
    const ok = await run(() => api.createUser(newName, newPassword), `Created user ${newName.trim().toLowerCase()}.`);
    if (ok) {
      setNewName("");
      setNewPassword("");
      load();
    }
  };

  const deleteUser = async (user) => {
    if (!window.confirm(`Delete user ${user.username}? They are signed out immediately.`)) return;
    if (await run(() => api.deleteUser(user.id), `Deleted user ${user.username}.`)) load();
  };

  const saveReset = async () => {
    const ok = await run(() => api.resetUserPassword(resetting.id, resetPassword),
      `Password for ${resetting.username} changed. Their existing sessions were signed out.`);
    if (ok) {
      setResetting(null);
      setResetPassword("");
    }
  };

  const changeMine = async (e) => {
    e.preventDefault();
    if (next !== confirm) {
      setNotice("");
      setError("New passwords do not match.");
      return;
    }
    const ok = await run(async () => startSession(await api.changeMyPassword(current, next)),
      "Your password was changed. Other sessions were signed out.");
    if (ok) {
      setCurrent("");
      setNext("");
      setConfirm("");
    }
  };

  const formatDate = (iso) => (iso ? new Date(iso).toLocaleString() : "—");

  return (
    <div className="max-w-3xl">
      <h1 className="text-2xl font-bold mb-4">Users</h1>
      <p className="text-gray-700 mb-4">
        Every user here is an administrator with full access to all tenants.
      </p>

      {error && <div className="mb-4 p-3 rounded bg-red-100 text-red-800" role="alert">{error}</div>}
      {notice && <div className="mb-4 p-3 rounded bg-green-100 text-green-800" role="status">{notice}</div>}

      <div className="bg-white rounded shadow mb-6">
        <table className="w-full text-left">
          <thead className="border-b text-sm text-gray-500">
            <tr>
              <th className="p-3">Username</th>
              <th className="p-3">Created</th>
              <th className="p-3 text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {users.map(u => (
              <tr key={u.id} className="border-b last:border-0">
                <td className="p-3 font-semibold">
                  {u.username}
                  {u.username === me && <span className="ml-2 text-xs font-normal text-gray-500">(you)</span>}
                </td>
                <td className="p-3 text-sm text-gray-600">{formatDate(u.createdAt)}</td>
                <td className="p-3 text-right space-x-3">
                  {u.username !== me && (
                    <>
                      <button className="text-sm text-blue-600" onClick={() => setResetting(u)}>
                        Reset password
                      </button>
                      <button className="text-sm text-red-600" onClick={() => deleteUser(u)}>
                        Delete
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        <form onSubmit={createUser} className="bg-white p-4 rounded shadow">
          <h2 className="font-semibold mb-2">Add user</h2>
          <input
            className="border p-2 w-full mb-2 rounded"
            placeholder="Username"
            autoComplete="off"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
          />
          <input
            type="password"
            className="border p-2 w-full mb-1 rounded"
            placeholder="Initial password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
          />
          <p className="text-xs text-gray-500 mb-2">At least {MIN_PASSWORD} characters.</p>
          <button className="bg-blue-600 text-white px-4 py-2 rounded">Add user</button>
        </form>

        <form onSubmit={changeMine} className="bg-white p-4 rounded shadow">
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
            className="border p-2 w-full mb-2 rounded"
            placeholder="Confirm new password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
          <button className="bg-slate-900 text-white px-4 py-2 rounded">Change password</button>
        </form>
      </div>

      {resetting && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center">
          <div className="bg-white p-4 rounded shadow w-full max-w-md">
            <h3 className="font-semibold mb-2">Reset password for {resetting.username}</h3>
            <p className="text-sm text-gray-600 mb-2">
              They will be signed out everywhere and must log in with the new password.
            </p>
            <input
              type="password"
              className="border p-2 w-full mb-2 rounded"
              placeholder="New password"
              autoComplete="new-password"
              value={resetPassword}
              onChange={(e) => setResetPassword(e.target.value)}
            />
            <div className="flex justify-end gap-2">
              <button
                className="px-3 py-1 rounded border"
                onClick={() => { setResetting(null); setResetPassword(""); }}
              >
                Cancel
              </button>
              <button className="px-3 py-1 rounded bg-blue-600 text-white" onClick={saveReset}>
                Save
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
