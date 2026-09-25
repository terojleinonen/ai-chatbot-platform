import { createContext, useContext, useState } from "react";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  // Read synchronously so PrivateRoute doesn't redirect to /login on reload before the token is loaded.
  const [token, setToken] = useState(() => localStorage.getItem("admin_token"));

  const login = async (password) => {
    const correct = import.meta.env.VITE_ADMIN_PASSWORD || "demo123";
    if (password === correct) {
      const fakeToken = "demo-token";
      localStorage.setItem("admin_token", fakeToken);
      setToken(fakeToken);
      return true;
    }
    return false;
  };

  const logout = () => {
    localStorage.removeItem("admin_token");
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
