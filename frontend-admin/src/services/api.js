const API_BASE = "http://localhost:8080";

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
  trainAi: async (tenantId, faqs) => {
    const r = await fetch(`http://localhost:8081/ai/train/${tenantId}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-API-KEY": "MY_INTERNAL_AI_KEY"
      },
      body: JSON.stringify(faqs)
    });
    return r.text();
  }
};
