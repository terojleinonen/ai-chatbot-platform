export const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";
export const WS_URL = import.meta.env.VITE_WS_URL || API_BASE.replace(/^http/, "ws") + "/ws";

export const api = {
  listTenants: async () => {
    const r = await fetch(`${API_BASE}/tenants/list`);
    return r.json();
  },
  createTenant: async (name) => {
    const r = await fetch(`${API_BASE}/tenants/create`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name })
    });
    return r.json();
  },
  getFaqs: async (tenantId) => {
    const r = await fetch(`${API_BASE}/faq/list/${tenantId}`);
    return r.json();
  },
  addFaq: async (tenantId, question, answer) => {
    const r = await fetch(`${API_BASE}/faq/create`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tenantId, question, answer })
    });
    return r.json();
  },
  updateFaq: async (id, fields) => {
    const r = await fetch(`${API_BASE}/faq/${id}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(fields)
    });
    return r.json();
  },
  deleteFaq: async (id) => {
    await fetch(`${API_BASE}/faq/${id}`, { method: "DELETE" });
  },
  // Replaces all FAQs of the tenant with the given rows and retrains the AI.
  importFaqs: async (tenantId, faqs) => {
    const r = await fetch(`${API_BASE}/faq/import/${tenantId}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(faqs)
    });
    if (!r.ok) throw new Error(`Import failed (${r.status})`);
    return r.json();
  },
  trainAi: async (tenantId) => {
    const r = await fetch(`${API_BASE}/faq/train/${tenantId}`, { method: "POST" });
    const body = await r.json();
    if (!r.ok) throw new Error(body.message || `Training failed (${r.status})`);
    return body.message;
  }
};
