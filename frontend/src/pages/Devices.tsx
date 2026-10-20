import React, { useEffect, useState } from 'react';
import { devicesApi, syncApi } from '../api';
import type { DeviceResponse, SyncOperationResponse } from '../types';

export default function Devices() {
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [ops, setOps] = useState<SyncOperationResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [newDeviceName, setNewDeviceName] = useState('');
  const [newDeviceType, setNewDeviceType] = useState('LAPTOP');
  const [error, setError] = useState('');

  const load = () => {
    Promise.all([devicesApi.list(), syncApi.getOperations()])
      .then(([d, o]) => { setDevices(d.data.data); setOps(o.data.data); })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const register = async () => {
    if (!newDeviceName.trim()) return;
    try {
      await devicesApi.register(newDeviceName.trim(), newDeviceType);
      setNewDeviceName('');
      load();
    } catch (e: any) {
      setError(e.response?.data?.message || 'Failed to register device');
    }
  };

  const toggleStatus = async (device: DeviceResponse) => {
    const next = device.status === 'ONLINE' ? 'OFFLINE' : 'ONLINE';
    await devicesApi.updateStatus(device.id, next);
    load();
  };

  const deleteDevice = async (id: number) => {
    if (!confirm('Delete this device?')) return;
    await devicesApi.delete(id);
    load();
  };

  const deviceIcon: Record<string, string> = {
    LAPTOP: '💻', DESKTOP: '🖥', PHONE: '📱', TABLET: '📟', OTHER: '📡',
  };

  if (loading) return <div className="page-body text-muted">Loading…</div>;

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Devices</h1>
      </div>
      <div className="page-body">
        {error && <div className="alert alert-error">{error}</div>}

        {/* Register device */}
        <div className="card mb-4">
          <div className="card-title">Register New Device</div>
          <div className="gap-2 align-center">
            <input className="form-input flex-1" placeholder="Device name (e.g. Laptop, Desktop)"
              value={newDeviceName} onChange={e => setNewDeviceName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && register()} />
            <select className="form-select" value={newDeviceType}
              onChange={e => setNewDeviceType(e.target.value)} style={{ width: 140 }}>
              {['LAPTOP', 'DESKTOP', 'PHONE', 'TABLET', 'OTHER'].map(t => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
            <button className="btn btn-primary" onClick={register}>Register</button>
          </div>
        </div>

        {/* Device list */}
        <div className="card mb-4">
          <div className="card-title">Your Devices</div>
          {devices.length === 0 && <div className="text-muted">No devices registered yet.</div>}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
            {devices.map(d => (
              <div key={d.id} style={{
                display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0',
                borderBottom: '1px solid var(--border)'
              }}>
                <span style={{ fontSize: 24 }}>{deviceIcon[d.deviceType] || '📡'}</span>
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600 }}>{d.deviceName}</div>
                  <div className="text-muted mono" style={{ fontSize: 11 }}>{d.deviceToken.slice(0, 16)}…</div>
                </div>
                <span className={`badge badge-${d.status.toLowerCase()}`}>{d.status}</span>
                {d.lastSeenAt && (
                  <span className="text-muted" style={{ fontSize: 12 }}>
                    Last seen: {new Date(d.lastSeenAt).toLocaleString()}
                  </span>
                )}
                <div className="gap-2">
                  <button className={`btn btn-sm ${d.status === 'ONLINE' ? 'btn-outline' : 'btn-success'}`}
                    onClick={() => toggleStatus(d)}>
                    {d.status === 'ONLINE' ? '⏸ Go Offline' : '▶ Go Online'}
                  </button>
                  <button className="btn btn-danger btn-sm" onClick={() => deleteDevice(d.id)}>Delete</button>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Sync Demo Instructions */}
        <div className="card" style={{ borderColor: 'rgba(210,153,34,.4)', background: 'rgba(210,153,34,.04)' }}>
          <div className="card-title" style={{ color: 'var(--accent-orange)' }}>⚡ Conflict Demo Walkthrough</div>
          <ol style={{ paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 6, fontSize: 13 }}>
            {[
              'Register two devices: Laptop and Desktop',
              'Upload a file from Laptop (it becomes v1)',
              'Take BOTH devices OFFLINE using the "Go Offline" button',
              'Queue a SYNC operation from Laptop (will run when it goes online)',
              'Queue a SYNC operation from Desktop with baseVersion=1',
              'Put Laptop ONLINE — background worker picks up its op → no conflict (v1 == v1)',
              'Simulate Laptop advancing the server to v2 (upload new version)',
              'Put Desktop ONLINE — background worker checks: base=1 ≠ server=2 → CONFLICT!',
              'Go to the Conflicts page and choose: KEEP_SERVER, KEEP_LOCAL, or CREATE_CONFLICT_COPY',
            ].map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </div>

        {/* Pending operations */}
        {ops.length > 0 && (
          <div className="card mt-4">
            <div className="card-title">Sync Operations</div>
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr><th>Device</th><th>File</th><th>Operation</th><th>Status</th><th>Base Version</th><th>Retries</th></tr>
                </thead>
                <tbody>
                  {ops.map(op => (
                    <tr key={op.id}>
                      <td>{devices.find(d => d.id === op.deviceId)?.deviceName || op.deviceId}</td>
                      <td>{op.fileId || '—'}</td>
                      <td>{op.operation}</td>
                      <td><span className={`badge badge-${op.status.toLowerCase()}`}>{op.status}</span></td>
                      <td>{op.baseVersion ?? '—'}</td>
                      <td>{op.retryCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
