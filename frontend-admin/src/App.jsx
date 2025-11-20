import { Routes, Route, Link } from "react-router-dom";
import PrivateRoute from "./auth/PrivateRoute.jsx";
import { useAuth } from "./auth/AuthContext.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import Dashboard from "./pages/Dashboard.jsx";
import TenantsPage from "./pages/TenantsPage.jsx";
import FaqPage from "./pages/FaqPage.jsx";
import ChatWsPage from "./pages/ChatWsPage.jsx";
import ImportExportPage from "./pages/ImportExportPage.jsx";

function Layout({ children }) {
  const { logout } = useAuth();
  return (
    <div className="min-h-screen flex bg-gray-100">
      <aside className="w-64 bg-slate-900 text-white flex flex-col">
        <div className="p-4 text-xl font-bold border-b border-slate-700">
          AI Chatbot Admin
        </div>
        <nav className="flex-1 p-4 space-y-2">
          <Link className="block px-3 py-2 rounded hover:bg-slate-800" to="/dashboard">
            Dashboard
          </Link>
          <Link className="block px-3 py-2 rounded hover:bg-slate-800" to="/tenants">
            Tenants
          </Link>
          <Link className="block px-3 py-2 rounded hover:bg-slate-800" to="/faqs">
            FAQs
          </Link>
          <Link className="block px-3 py-2 rounded hover:bg-slate-800" to="/chat">
            Chat (WebSocket)
          </Link>
          <Link className="block px-3 py-2 rounded hover:bg-slate-800" to="/import-export">
            Import / Export
          </Link>
        </nav>
        <button
          onClick={logout}
          className="m-4 mt-auto px-3 py-2 bg-red-600 rounded hover:bg-red-500 text-sm"
        >
          Logout
        </button>
      </aside>
      <main className="flex-1 p-6">{children}</main>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/*"
        element={
          <PrivateRoute>
            <Layout>
              <Routes>
                <Route path="/dashboard" element={<Dashboard />} />
                <Route path="/tenants" element={<TenantsPage />} />
                <Route path="/faqs" element={<FaqPage />} />
                <Route path="/chat" element={<ChatWsPage />} />
                <Route path="/import-export" element={<ImportExportPage />} />
                <Route path="*" element={<Dashboard />} />
              </Routes>
            </Layout>
          </PrivateRoute>
        }
      />
    </Routes>
  );
}
