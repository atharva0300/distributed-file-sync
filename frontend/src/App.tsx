import React, { useState, useEffect, createContext, useContext } from 'react';
import { BrowserRouter, Routes, Route, Navigate, NavLink } from 'react-router-dom';
import { useSyncEvents, type SyncEvent } from './hooks/useSyncEvents';
import Dashboard from './pages/Dashboard';
import Files from './pages/Files';
import Devices from './pages/Devices';
import Versions from './pages/Versions';
import Conflicts from './pages/Conflicts';
import Login from './pages/Login';
import Register from './pages/Register';

// ── Auth Context ─────────────────────────────────────────────────────────────
interface AuthUser { userId: number; email: string; token: string; }
interface AuthCtx { user: AuthUser | null; login: (u: AuthUser) => void; logout: () => void; }

export const AuthContext = createContext<AuthCtx>({ user: null, login: () => {}, logout: () => {} });
export const useAuth = () => useContext(AuthContext);

// ── Toast Context ────────────────────────────────────────────────────────────
interface Toast { id: number; event: SyncEvent; }
export const ToastContext = createContext<(e: SyncEvent) => void>(() => {});

// ── App Shell ────────────────────────────────────────────────────────────────
function AppShell({ user }: { user: AuthUser }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const pushToast = (event: SyncEvent) => {
    const id = Date.now();
    setToasts(prev => [...prev.slice(-4), { id, event }]);
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 4000);
  };

  useSyncEvents(user.userId, pushToast);
  const { logout } = useAuth();

  return (
    <ToastContext.Provider value={pushToast}>
      <div className="app-shell">
        <nav className="sidebar">
          <div className="sidebar-logo">File<span>Sync</span></div>
          <NavLink className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`} to="/dashboard">
            📊 Dashboard
          </NavLink>
          <NavLink className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`} to="/files">
            📁 Files
          </NavLink>
          <NavLink className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`} to="/devices">
            💻 Devices
          </NavLink>
          <NavLink className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`} to="/conflicts">
            ⚡ Conflicts
          </NavLink>
          <div style={{ flex: 1 }} />
          <div style={{ padding: '8px 16px', borderTop: '1px solid var(--border)' }}>
            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
              {user.email}
            </div>
            <button className="btn btn-outline btn-sm" onClick={logout} style={{ width: '100%' }}>
              Sign out
            </button>
          </div>
        </nav>
        <div className="main-content">
          <Routes>
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/files" element={<Files />} />
            <Route path="/files/:fileId/versions" element={<Versions />} />
            <Route path="/devices" element={<Devices />} />
            <Route path="/conflicts" element={<Conflicts />} />
            <Route path="*" element={<Navigate to="/dashboard" />} />
          </Routes>
        </div>
      </div>

      {/* Toast notifications */}
      <div className="toast-container">
        {toasts.map(t => (
          <div key={t.id} className="toast">
            <div className="toast-event">{t.event.eventType}</div>
            <div>{t.event.message}</div>
            {t.event.fileName && (
              <div className="text-muted" style={{ fontSize: 12 }}>{t.event.fileName}</div>
            )}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

// ── Root App ─────────────────────────────────────────────────────────────────
export default function App() {
  const [user, setUser] = useState<AuthUser | null>(() => {
    const stored = localStorage.getItem('user');
    return stored ? JSON.parse(stored) : null;
  });

  const login = (u: AuthUser) => {
    localStorage.setItem('token', u.token);
    localStorage.setItem('user', JSON.stringify(u));
    setUser(u);
  };

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={user ? <Navigate to="/dashboard" /> : <Login />} />
          <Route path="/register" element={user ? <Navigate to="/dashboard" /> : <Register />} />
          <Route path="/*" element={user ? <AppShell user={user} /> : <Navigate to="/login" />} />
        </Routes>
      </BrowserRouter>
    </AuthContext.Provider>
  );
}
