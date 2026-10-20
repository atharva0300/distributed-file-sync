import React, { useEffect, useState } from 'react';
import { devicesApi, syncApi, filesApi } from '../api';
import { useAuth } from '../App';
import type { DeviceResponse, SyncLogResponse, FileResponse } from '../types';

export default function Dashboard() {
  const { user } = useAuth();
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [logs, setLogs] = useState<SyncLogResponse[]>([]);
  const [files, setFiles] = useState<FileResponse[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([devicesApi.list(), syncApi.getLogs(), filesApi.list()])
      .then(([d, l, f]) => {
        setDevices(d.data.data);
        setLogs(l.data.data.slice(0, 15));
        setFiles(f.data.data.filter(f => !f.isFolder));
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="page-body text-muted">Loading…</div>;

  const conflicts = files.filter(f => f.syncStatus === 'CONFLICT').length;
  const online = devices.filter(d => d.status === 'ONLINE').length;

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Dashboard</h1>
        <span className="text-muted" style={{ fontSize: 13 }}>{user?.email}</span>
      </div>
      <div className="page-body">
        {/* Stats */}
        <div className="grid-3 mb-4">
          <div className="card stat-card">
            <div className="stat-value">{devices.length}</div>
            <div className="stat-label">Registered Devices</div>
          </div>
          <div className="card stat-card">
            <div className="stat-value" style={{ color: 'var(--accent-green)' }}>{online}</div>
            <div className="stat-label">Online Devices</div>
          </div>
          <div className="card stat-card">
            <div className="stat-value" style={{ color: conflicts > 0 ? 'var(--accent-red)' : 'var(--text-primary)' }}>
              {conflicts}
            </div>
            <div className="stat-label">Active Conflicts</div>
          </div>
        </div>

        <div className="grid-2">
          {/* Devices */}
          <div className="card">
            <div className="card-title">Devices</div>
            {devices.length === 0 && <div className="text-muted">No devices registered. <a href="/devices">Add one.</a></div>}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {devices.map(d => (
                <div key={d.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <span style={{ fontWeight: 500 }}>{d.deviceName}</span>
                    <span className="text-muted" style={{ marginLeft: 8, fontSize: 12 }}>{d.deviceType}</span>
                  </div>
                  <span className={`badge badge-${d.status.toLowerCase()}`}>{d.status}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Recent Sync Activity */}
          <div className="card">
            <div className="card-title">Recent Activity</div>
            {logs.length === 0 && <div className="text-muted">No sync activity yet.</div>}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {logs.slice(0, 8).map(log => (
                <div key={log.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 13 }}>
                  <div>
                    <span className={`badge badge-${log.status === 'SUCCESS' ? 'synced' : log.status === 'FAILED' ? 'failed' : 'pending'}`} style={{ marginRight: 8 }}>
                      {log.status}
                    </span>
                    <span>{log.operation}</span>
                    {log.fileName && <span className="text-muted"> · {log.fileName}</span>}
                  </div>
                  <span className="text-muted mono">{new Date(log.timestamp).toLocaleTimeString()}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Architecture note */}
        <div className="card mt-4" style={{ borderColor: 'rgba(88,166,255,.3)', background: 'rgba(88,166,255,.03)' }}>
          <div className="card-title" style={{ color: 'var(--accent-blue)' }}>Architecture</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16, fontSize: 12 }}>
            {[
              { label: 'Chunking', desc: '4 MB fixed-size chunks' },
              { label: 'SHA-256', desc: 'Content-addressed storage' },
              { label: 'Dedup', desc: 'Hash-based deduplication' },
              { label: 'Delta Sync', desc: 'Upload only changed chunks' },
              { label: 'Versioning', desc: 'MongoDB version history' },
              { label: 'Conflict', desc: 'Optimistic locking + baseVersion' },
              { label: 'Offline', desc: 'Queue + background worker' },
              { label: 'WebSocket', desc: 'STOMP real-time events' },
            ].map(item => (
              <div key={item.label} style={{ background: 'var(--bg-card)', padding: '10px 12px', borderRadius: 8 }}>
                <div style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: 4 }}>{item.label}</div>
                <div className="text-muted">{item.desc}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}
