export const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";
export const WS_URL = import.meta.env.VITE_WS_URL || API_BASE.replace(/^http/, "ws") + "/ws";

const TOKEN_KEY = "admin_token";
const EXPIRES_KEY = "admin_token_expires";

export function getToken() {
  const token = localStorage.getItem(TOKEN_KEY);
  const expires = localStorage.getItem(EXPIRES_KEY);
  if (!token || (expires && Date.parse(expires) <= Date.now())) {
    clearToken();
    return null;
  }
  return token;
}

export function setToken(token, expiresAt) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(EXPIRES_KEY, expiresAt);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(EXPIRES_KEY);
}

// Authenticated fetch: adds the bearer token and sends the user back to login when it is rejected.
async function request(path, { json, ...options } = {}) {
  const headers = { ...options.headers };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (json !== undefined) {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(json);
  }
  const r = await fetch(`${API_BASE}${path}`, { ...options, headers });
  if (r.status === 401) {
    clearToken();
    window.location.assign("/login");
    throw new Error("Session expired");
  }
  return r;
}

export const api = {
  login: async (username, password) => {
    const r = await fetch(`${API_BASE}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password })
    });
    if (!r.ok) return null;
    return r.json();
  },
  listTenants: async () => {
    const r = await request("/tenants/list");
    return r.json();
  },
  createTenant: async (name) => {
    const r = await request("/tenants/create", { method: "POST", json: { name } });
    return r.json();
  },
  getFaqs: async (tenantId) => {
    const r = await request(`/faq/list/${tenantId}`);
    return r.json();
  },
  addFaq: async (tenantId, question, answer) => {
    const r = await request("/faq/create", { method: "POST", json: { tenantId, question, answer } });
    return r.json();
  },
  updateFaq: async (id, fields) => {
    const r = await request(`/faq/${id}`, { method: "PUT", json: fields });
    return r.json();
  },
  deleteFaq: async (id) => {
    await request(`/faq/${id}`, { method: "DELETE" });
  },
  // Replaces all FAQs of the tenant with the given rows and retrains the AI.
  importFaqs: async (tenantId, faqs) => {
    const r = await request(`/faq/import/${tenantId}`, { method: "POST", json: faqs });
    if (!r.ok) throw new Error(`Import failed (${r.status})`);
    return r.json();
  },
  trainAi: async (tenantId) => {
    const r = await request(`/faq/train/${tenantId}`, { method: "POST" });
    const body = await r.json();
    if (!r.ok) throw new Error(body.message || `Training failed (${r.status})`);
    return body.message;
  }
};
