import { useEffect, useState } from "react";
import { api } from "../services/api";
import { useAuth } from "../auth/AuthContext.jsx";

const MIN_PASSWORD = 12;
const ROLE_LABELS = { SUPER_ADMIN: "Super admin", TENANT_ADMIN: "Tenant admin" };

// Role selector plus tenant checkboxes (tenants only apply to tenant admins).
function AccessFields({ role, setRole, tenantIds, setTenantIds, tenants }) {
  const toggle = (id) =>
    setTenantIds(tenantIds.includes(id) ? tenantIds.filter(t => t !== id) : [...tenantIds, id]);
  return (
    <>
      <select
        className="border p-2 w-full mb-2 rounded"
        aria-label="Role"
        value={role}
        onChange={(e) => setRole(e.target.value)}
      >
        <option value="TENANT_ADMIN">Tenant admin – only assigned tenants</option>
        <option value="SUPER_ADMIN">Super admin – all tenants and users</option>
      </select>
      {role === "TENANT_ADMIN" && (
        <fieldset className="border rounded p-2 mb-2">
          <legend className="text-sm text-gray-600 px-1">Tenants</legend>
          <div className="max-h-40 overflow-y-auto">
            {tenants.length === 0 && <div className="text-sm text-gray-500">No tenants yet.</div>}
            {tenants.map(t => (
              <label key={t.id} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={tenantIds.includes(t.id)} onChange={() => toggle(t.id)} />
                {t.name}
              </label>
            ))}
          </div>
        </fieldset>
      )}
    </>
  );
}

export default function UsersPage() {
  const { username: me } = useAuth();
  const [users, setUsers] = useState([]);
  const [tenants, setTenants] = useState([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const [newName, setNewName] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newRole, setNewRole] = useState("TENANT_ADMIN");
  const [newTenantIds, setNewTenantIds] = useState([]);

  const [editing, setEditing] = useState(null);
  const [editRole, setEditRole] = useState("TENANT_ADMIN");
  const [editTenantIds, setEditTenantIds] = useState([]);

  const [resetting, setResetting] = useState(null);
  const [resetPassword, setResetPassword] = useState("");

  const load = () => api.listUsers().then(setUsers).catch(e => setError(e.message));

  useEffect(() => {
    load();
    api.listTenants().then(setTenants);
  }, []);

  const tenantName = (id) => tenants.find(t => t.id === id)?.name ?? `#${id}`;

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
    const ok = await run(() => api.createUser(newName, newPassword, newRole, newTenantIds),
      `Created user ${newName.trim().toLowerCase()}.`);
    if (ok) {
      setNewName("");
      setNewPassword("");
      setNewRole("TENANT_ADMIN");
      setNewTenantIds([]);
      load();
    }
  };

  const startEdit = (user) => {
    setEditing(user);
    setEditRole(user.role);
    setEditTenantIds(user.tenantIds);
  };

  const saveAccess = async () => {
    const ok = await run(async () => {
      const updated = await api.updateUserAccess(editing.id, editRole, editTenantIds);
      setUsers(prev => prev.map(u => (u.id === updated.id ? updated : u)));
    }, `Access for ${editing.username} updated. It applies to their next request.`);
    if (ok) setEditing(null);
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

  const formatDate = (iso) => (iso ? new Date(iso).toLocaleString() : "—");

  const accessSummary = (u) => {
    if (u.role === "SUPER_ADMIN") return "All tenants";
    if (u.tenantIds.length === 0) return "No tenants";
    return u.tenantIds.map(tenantName).join(", ");
  };

  return (
    <div className="max-w-4xl">
      <h1 className="text-2xl font-bold mb-4">Users</h1>
      <p className="text-gray-700 mb-4">
        Super admins manage users and tenants and can access every tenant. Tenant admins can only manage
        the tenants assigned to them. To change your own password, use <b>My account</b>.
      </p>

      {error && <div className="mb-4 p-3 rounded bg-red-100 text-red-800" role="alert">{error}</div>}
      {notice && <div className="mb-4 p-3 rounded bg-green-100 text-green-800" role="status">{notice}</div>}

      <div className="bg-white rounded shadow mb-6">
        <table className="w-full text-left">
          <thead className="border-b text-sm text-gray-500">
            <tr>
              <th className="p-3">Username</th>
              <th className="p-3">Role</th>
              <th className="p-3">Tenants</th>
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
                <td className="p-3 text-sm">{ROLE_LABELS[u.role] || u.role}</td>
                <td className="p-3 text-sm text-gray-600">{accessSummary(u)}</td>
                <td className="p-3 text-sm text-gray-600">{formatDate(u.createdAt)}</td>
                <td className="p-3 text-right space-x-3 whitespace-nowrap">
                  {u.username !== me && (
                    <>
                      <button className="text-sm text-blue-600" onClick={() => startEdit(u)}>
                        Edit access
                      </button>
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

      <form onSubmit={createUser} className="bg-white p-4 rounded shadow max-w-md">
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
        <AccessFields
          role={newRole} setRole={setNewRole}
          tenantIds={newTenantIds} setTenantIds={setNewTenantIds}
          tenants={tenants}
        />
        <button className="bg-blue-600 text-white px-4 py-2 rounded">Add user</button>
      </form>

      {editing && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center">
          <div className="bg-white p-4 rounded shadow w-full max-w-md">
            <h3 className="font-semibold mb-2">Access for {editing.username}</h3>
            <AccessFields
              role={editRole} setRole={setEditRole}
              tenantIds={editTenantIds} setTenantIds={setEditTenantIds}
              tenants={tenants}
            />
            <div className="flex justify-end gap-2">
              <button className="px-3 py-1 rounded border" onClick={() => setEditing(null)}>Cancel</button>
              <button className="px-3 py-1 rounded bg-blue-600 text-white" onClick={saveAccess}>Save</button>
            </div>
          </div>
        </div>
      )}

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
