export const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";
export const WS_URL = import.meta.env.VITE_WS_URL || API_BASE.replace(/^http/, "ws") + "/ws";

const TOKEN_KEY = "admin_token";
const EXPIRES_KEY = "admin_token_expires";
const USER_KEY = "admin_username";
const ROLE_KEY = "admin_role";

export function getToken() {
  const token = localStorage.getItem(TOKEN_KEY);
  const expires = localStorage.getItem(EXPIRES_KEY);
  if (!token || (expires && Date.parse(expires) <= Date.now())) {
    clearToken();
    return null;
  }
  return token;
}

export function getUsername() {
  return localStorage.getItem(USER_KEY);
}

export function getRole() {
  return localStorage.getItem(ROLE_KEY);
}

export function setRole(role) {
  if (role) localStorage.setItem(ROLE_KEY, role);
}

export function setToken(token, expiresAt, username) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(EXPIRES_KEY, expiresAt);
  if (username) localStorage.setItem(USER_KEY, username);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(EXPIRES_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(ROLE_KEY);
}

// Throws an Error carrying the backend's {"message"} for non-2xx responses.
async function ensureOk(r) {
  if (r.ok) return r;
  const body = await r.json().catch(() => ({}));
  throw new Error(body.message || `Request failed (${r.status})`);
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
    // The page is being replaced; never settle so callers don't surface unhandled errors.
    return new Promise(() => {});
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
    const body = await r.json().catch(() => ({}));
    if (r.status === 429) {
      const seconds = Number(r.headers.get("Retry-After")) || body.retryAfterSeconds || 60;
      return { error: "rate_limited", retryAfterSeconds: seconds };
    }
    if (!r.ok) return { error: "invalid_credentials" };
    return body;
  },
  // Current user with fresh role and tenant assignments.
  getMe: async () => {
    const r = await ensureOk(await request("/auth/me"));
    return r.json();
  },
  listTenants: async () => {
    const r = await request("/tenants/list");
    return r.json();
  },
  createTenant: async (name) => {
    const r = await ensureOk(await request("/tenants/create", { method: "POST", json: { name } }));
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
  listUsers: async () => {
    const r = await ensureOk(await request("/users"));
    return r.json();
  },
  createUser: async (username, password, role, tenantIds) => {
    const r = await ensureOk(await request("/users", {
      method: "POST", json: { username, password, role, tenantIds }
    }));
    return r.json();
  },
  updateUserAccess: async (id, role, tenantIds) => {
    const r = await ensureOk(await request(`/users/${id}/access`, { method: "PUT", json: { role, tenantIds } }));
    return r.json();
  },
  resetUserPassword: async (id, password) => {
    await ensureOk(await request(`/users/${id}/password`, { method: "PUT", json: { password } }));
  },
  deleteUser: async (id) => {
    await ensureOk(await request(`/users/${id}`, { method: "DELETE" }));
  },
  // Returns a fresh session ({token, expiresAt, username}); the old token stops working.
  changeMyPassword: async (currentPassword, newPassword) => {
    const r = await ensureOk(await request("/users/me/password", {
      method: "PUT", json: { currentPassword, newPassword }
    }));
    return r.json();
  },
  trainAi: async (tenantId) => {
    const r = await request(`/faq/train/${tenantId}`, { method: "POST" });
    const body = await r.json();
    if (!r.ok) throw new Error(body.message || `Training failed (${r.status})`);
    return body.message;
  }
};
