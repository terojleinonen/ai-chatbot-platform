import { useEffect, useState } from "react";
import { api, API_BASE, WIDGET_URL } from "../services/api";
import { useAuth } from "../auth/AuthContext.jsx";

const embedSnippet = (tenant) => `<script src="${WIDGET_URL}"></script>
<script>
  ChatWidget.init({ backendUrl: "${API_BASE}", widgetKey: "${tenant.widgetKey}" });
</script>`;

// Widget key, embed snippet and allowed websites for one tenant.
function TenantCard({ tenant, onChange }) {
  const [origins, setOrigins] = useState(tenant.allowedOrigins.join("\n"));
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const run = async (action, success) => {
    setError("");
    setNotice("");
    try {
      const updated = await action();
      onChange(updated);
      setNotice(success);
    } catch (e) {
      setError(e.message);
    }
  };

  const saveOrigins = () => run(async () => {
    const updated = await api.updateTenantSettings(tenant.id, origins.split("\n").map(o => o.trim()).filter(Boolean));
    setOrigins(updated.allowedOrigins.join("\n"));
    return updated;
  }, "Allowed websites saved.");

  const rotate = () => {
    if (!window.confirm(`Create a new widget key for ${tenant.name}? Websites using the current key stop working until updated.`)) return;
    run(() => api.rotateWidgetKey(tenant.id), "New widget key created. Update the embed code on your websites.");
  };

  const copy = () => navigator.clipboard?.writeText(embedSnippet(tenant)).then(() => setNotice("Embed code copied."));

  return (
    <div className="bg-white p-4 rounded shadow" data-tenant-id={tenant.id}>
      <div className="flex justify-between items-start mb-2">
        <div>
          <div className="font-semibold">{tenant.name}</div>
          <div className="text-xs text-gray-500">ID: {tenant.id}</div>
        </div>
      </div>
      {error && <div className="mb-2 p-2 rounded bg-red-100 text-red-800 text-sm" role="alert">{error}</div>}
      {notice && <div className="mb-2 p-2 rounded bg-green-100 text-green-800 text-sm" role="status">{notice}</div>}

      <div className="text-sm font-semibold mb-1">Widget key</div>
      <div className="flex gap-2 items-center mb-3">
        <code className="bg-gray-100 px-2 py-1 rounded text-sm" data-testid="widget-key">{tenant.widgetKey}</code>
        <button className="text-sm text-red-600" onClick={rotate}>Rotate key</button>
      </div>

      <div className="text-sm font-semibold mb-1">Embed code</div>
      <pre className="bg-gray-100 p-2 rounded text-xs overflow-x-auto mb-1">{embedSnippet(tenant)}</pre>
      <button className="text-sm text-blue-600 mb-3" onClick={copy}>Copy embed code</button>

      <label className="block text-sm font-semibold mb-1" htmlFor={`origins-${tenant.id}`}>
        Allowed websites
      </label>
      <p className="text-xs text-gray-500 mb-1">
        One per line, e.g. https://www.example.com. Leave empty to allow any website.
      </p>
      <textarea
        id={`origins-${tenant.id}`}
        className="border p-2 w-full rounded text-sm font-mono mb-2"
        rows={3}
        value={origins}
        onChange={(e) => setOrigins(e.target.value)}
      />
      <button className="bg-blue-600 text-white px-3 py-1 rounded text-sm" onClick={saveOrigins}>
        Save websites
      </button>
    </div>
  );
}

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

  const replace = (updated) => setTenants(prev => prev.map(t => (t.id === updated.id ? updated : t)));

  return (
    <div className="max-w-3xl">
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
      <div className="space-y-3">
        {tenants.map(t => <TenantCard key={t.id} tenant={t} onChange={replace} />)}
      </div>
    </div>
  );
}
