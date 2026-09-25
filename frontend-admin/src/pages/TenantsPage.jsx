import { useEffect, useState } from "react";
import { api } from "../services/api";
import { useAuth } from "../auth/AuthContext.jsx";

export default function TenantsPage() {
  const [tenants, setTenants] = useState([]);
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const { isSuperAdmin } = useAuth();

  const load = () => api.listTenants().then(setTenants);

  useEffect(() => { load(); }, []);

  const create = async (e) => {
    e.preventDefault();
    if (!name) return;
    setError("");
    try {
      await api.createTenant(name);
      setName("");
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">Tenants</h1>
      {error && <div className="mb-4 p-3 rounded bg-red-100 text-red-800" role="alert">{error}</div>}
      {!isSuperAdmin && (
        <p className="text-gray-700 mb-4">
          {tenants.length
            ? "These are the tenants assigned to you. Ask a super admin to create new tenants."
            : "No tenants are assigned to you yet. Ask a super admin for access."}
        </p>
      )}
      {isSuperAdmin && <form onSubmit={create} className="flex gap-2 mb-4">
        <input
          className="border p-2 rounded flex-1"
          placeholder="New tenant name"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <button className="bg-blue-600 text-white px-4 py-2 rounded">
          Add
        </button>
      </form>}
      <div className="space-y-2">
        {tenants.map(t => (
          <div key={t.id} className="bg-white p-3 rounded shadow flex justify-between">
            <div>
              <div className="font-semibold">{t.name}</div>
              <div className="text-xs text-gray-500">ID: {t.id}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
