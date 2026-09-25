import { createContext, useContext, useEffect, useState } from "react";
import {
  api, getToken, getUsername, getRole, setRole as storeRole, setToken as storeToken, clearToken
} from "../services/api";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  // Read synchronously so PrivateRoute doesn't redirect to /login on reload before the token is loaded.
  const [token, setToken] = useState(() => getToken());
  const [username, setUsername] = useState(() => getUsername());
  const [role, setRole] = useState(() => getRole());

  const updateRole = (newRole) => {
    storeRole(newRole);
    setRole(newRole);
  };

  // The role can change while signed in; refresh it from the backend when the app loads.
  useEffect(() => {
    if (token) api.getMe().then(me => updateRole(me.role)).catch(() => {});
  }, [token]);

  // Stores a session returned by the backend ({token, expiresAt, username, role?}).
  const startSession = (session) => {
    storeToken(session.token, session.expiresAt, session.username);
    setToken(session.token);
    setUsername(session.username);
    if (session.role) updateRole(session.role);
  };

  const login = async (username, password) => {
    // Returns null on success, otherwise { error, retryAfterSeconds? }.
    const result = await api.login(username, password);
    if (result.error) return result;
    startSession(result);
    return null;
  };

  const logout = () => {
    clearToken();
    setToken(null);
    setUsername(null);
    setRole(null);
  };

  const isSuperAdmin = role === "SUPER_ADMIN";

  return (
    <AuthContext.Provider
      value={{ token, username, role, isSuperAdmin, isAuthenticated: !!token, login, logout, startSession }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
