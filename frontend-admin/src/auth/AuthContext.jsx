import { createContext, useContext, useState } from "react";
import { api, getToken, setToken as storeToken, clearToken } from "../services/api";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  // Read synchronously so PrivateRoute doesn't redirect to /login on reload before the token is loaded.
  const [token, setToken] = useState(() => getToken());

  const login = async (username, password) => {
    // Returns null on success, otherwise { error, retryAfterSeconds? }.
    const result = await api.login(username, password);
    if (result.error) return result;
    storeToken(result.token, result.expiresAt);
    setToken(result.token);
    return null;
  };

  const logout = () => {
    clearToken();
    setToken(null);
  };

  return (
    <AuthContext.Provider value={{ token, isAuthenticated: !!token, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
