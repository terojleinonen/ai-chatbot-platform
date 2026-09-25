import { createContext, useContext, useState } from "react";
import { api, getToken, getUsername, setToken as storeToken, clearToken } from "../services/api";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  // Read synchronously so PrivateRoute doesn't redirect to /login on reload before the token is loaded.
  const [token, setToken] = useState(() => getToken());
  const [username, setUsername] = useState(() => getUsername());

  // Stores a session returned by the backend ({token, expiresAt, username}).
  const startSession = (session) => {
    storeToken(session.token, session.expiresAt, session.username);
    setToken(session.token);
    setUsername(session.username);
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
  };

  return (
    <AuthContext.Provider value={{ token, username, isAuthenticated: !!token, login, logout, startSession }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
