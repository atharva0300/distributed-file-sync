import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../api';
import { useAuth } from '../App';

export default function Register() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await authApi.register(email, password);
      const data = res.data.data;
      login({ userId: data.userId, email: data.email, token: data.token });
    } catch (err: any) {
      setError(err.response?.data?.message || 'Registration failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card card">
        <div className="auth-logo">⚡ FileSync</div>
        <div className="auth-subtitle">Create your account</div>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label">Email</label>
            <input id="register-email" className="form-input" type="email" value={email}
              onChange={e => setEmail(e.target.value)} placeholder="you@example.com" required />
          </div>
          <div className="form-group">
            <label className="form-label">Password <span className="text-muted">(min. 8 chars)</span></label>
            <input id="register-password" className="form-input" type="password" value={password}
              onChange={e => setPassword(e.target.value)} placeholder="••••••••" required minLength={8} />
          </div>
          <button id="register-submit" className="btn btn-primary" type="submit"
            style={{ width: '100%' }} disabled={loading}>
            {loading ? 'Creating account…' : 'Create account'}
          </button>
        </form>
        <div className="auth-footer">
          Have an account? <Link to="/login">Sign in</Link>
        </div>
      </div>
    </div>
  );
}
