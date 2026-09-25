import { createContext, useContext, useState } from "react";
import { api, getToken, setToken as storeToken, clearToken } from "../services/api";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  // Read synchronously so PrivateRoute doesn't redirect to /login on reload before the token is loaded.
  const [token, setToken] = useState(() => getToken());

  const login = async (username, password) => {
    const result = await api.login(username, password);
    if (!result) return false;
    storeToken(result.token, result.expiresAt);
    setToken(result.token);
    return true;
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
