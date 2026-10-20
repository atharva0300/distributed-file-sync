import React, { useEffect, useState } from 'react';
import { conflictsApi, filesApi } from '../api';
import type { ConflictResponse, FileResponse } from '../types';

type Strategy = 'KEEP_SERVER' | 'KEEP_LOCAL' | 'CREATE_CONFLICT_COPY';

export default function Conflicts() {
  const [conflicts, setConflicts] = useState<ConflictResponse[]>([]);
  const [files, setFiles] = useState<Record<number, FileResponse>>({});
  const [loading, setLoading] = useState(true);
  const [resolving, setResolving] = useState<number | null>(null);
  const [success, setSuccess] = useState('');

  const load = () => {
    conflictsApi.list().then(async (r) => {
      const data = r.data.data;
      setConflicts(data);
      // Fetch file metadata for each conflict
      const fileMap: Record<number, FileResponse> = {};
      await Promise.all(data.map(c =>
        filesApi.get(c.fileId).then(f => { fileMap[c.fileId] = f.data.data; }).catch(() => {})
      ));
      setFiles(fileMap);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const resolve = async (conflict: ConflictResponse, strategy: Strategy) => {
    setResolving(conflict.operationId);
    try {
      const res = await conflictsApi.resolve(conflict.operationId, strategy);
      setSuccess(res.data.data);
      load();
    } catch (e: any) {
      alert(e.response?.data?.message || 'Resolution failed');
    } finally {
      setResolving(null);
    }
  };

  if (loading) return <div className="page-body text-muted">Loading…</div>;

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">
          ⚡ Conflicts
          {conflicts.length > 0 && (
            <span className="badge badge-conflict" style={{ marginLeft: 12, fontSize: 13 }}>
              {conflicts.length}
            </span>
          )}
        </h1>
      </div>
      <div className="page-body">
        {success && <div className="alert alert-success">{success}</div>}

        {conflicts.length === 0 ? (
          <div className="card" style={{ textAlign: 'center', padding: 40 }}>
            <div style={{ fontSize: 48, marginBottom: 12 }}>✅</div>
            <div style={{ fontWeight: 600, fontSize: 16 }}>No active conflicts</div>
            <div className="text-muted mt-4">
              Conflicts occur when two devices independently modify the same file
              while both are offline (based on the same version).
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {conflicts.map(conflict => {
              const file = files[conflict.fileId];
              return (
                <div key={conflict.operationId} className="card" style={{
                  borderLeft: '3px solid var(--accent-red)'
                }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
                    <div>
                      <div className="gap-2 align-center mb-3">
                        <span className="badge badge-conflict">CONFLICT</span>
                        <span style={{ fontWeight: 600 }}>{file?.name || `File #${conflict.fileId}`}</span>
                      </div>
                      <div className="text-muted" style={{ fontSize: 13, marginBottom: 8 }}>
                        {conflict.message}
                      </div>
                      <div style={{ fontSize: 12, display: 'flex', gap: 16 }}>
                        <span><strong>Client base version:</strong> v{conflict.clientBaseVersion ?? '?'}</span>
                        <span><strong>Server version:</strong> v{file?.currentVersion ?? '?'}</span>
                        <span><strong>Device:</strong> {conflict.deviceId}</span>
                      </div>
                      <div className="text-muted mono" style={{ fontSize: 11, marginTop: 6 }}>
                        Detected at: {new Date(conflict.detectedAt).toLocaleString()}
                      </div>
                    </div>
                  </div>

                  {/* Resolution strategies */}
                  <div style={{ background: 'var(--bg-secondary)', borderRadius: 8, padding: 16 }}>
                    <div className="card-title">Choose Resolution Strategy</div>
                    <div className="grid-3" style={{ gap: 12 }}>
                      <div className="card" style={{ cursor: 'pointer' }} onClick={() => resolve(conflict, 'KEEP_SERVER')}>
                        <div style={{ fontWeight: 600, marginBottom: 6, color: 'var(--accent-blue)' }}>🖥 Keep Server</div>
                        <div className="text-muted" style={{ fontSize: 12 }}>
                          Discard the client's offline changes. Server version v{file?.currentVersion} remains current.
                        </div>
                        <button
                          className="btn btn-primary btn-sm"
                          style={{ marginTop: 12, width: '100%' }}
                          disabled={resolving === conflict.operationId}>
                          Keep Server
                        </button>
                      </div>

                      <div className="card" style={{ cursor: 'pointer' }} onClick={() => resolve(conflict, 'KEEP_LOCAL')}>
                        <div style={{ fontWeight: 600, marginBottom: 6, color: 'var(--accent-green)' }}>💻 Keep Local</div>
                        <div className="text-muted" style={{ fontSize: 12 }}>
                          Accept the client's version as the new current version. Previous server version is preserved in history.
                        </div>
                        <button
                          className="btn btn-success btn-sm"
                          style={{ marginTop: 12, width: '100%' }}
                          disabled={resolving === conflict.operationId}>
                          Keep Local
                        </button>
                      </div>

                      <div className="card" style={{ cursor: 'pointer' }} onClick={() => resolve(conflict, 'CREATE_CONFLICT_COPY')}>
                        <div style={{ fontWeight: 600, marginBottom: 6, color: 'var(--accent-orange)' }}>📋 Conflict Copy</div>
                        <div className="text-muted" style={{ fontSize: 12 }}>
                          Keep server version AND create a copy named "{file?.name.replace('.', ' (conflict-copy-device).')}". Both survive.
                        </div>
                        <button
                          className="btn btn-outline btn-sm"
                          style={{ marginTop: 12, width: '100%', borderColor: 'var(--accent-orange)', color: 'var(--accent-orange)' }}
                          disabled={resolving === conflict.operationId}>
                          Create Conflict Copy
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {/* Explanation card */}
        <div className="card mt-4" style={{ borderColor: 'rgba(88,166,255,.3)', background: 'rgba(88,166,255,.03)' }}>
          <div className="card-title">How Conflict Detection Works</div>
          <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 2 }}>
            <p>When a device queues a sync operation, it records the <strong style={{ color: 'var(--text-primary)' }}>baseVersion</strong> — the version it was based on when making the change.</p>
            <p>The background worker checks: if <code style={{ background: 'var(--bg-hover)', padding: '1px 6px', borderRadius: 4 }}>baseVersion ≠ server.currentVersion</code>, another device updated the file first.</p>
            <p>This is <strong style={{ color: 'var(--text-primary)' }}>optimistic concurrency control</strong> — no locks held during editing, conflicts resolved at sync time.</p>
          </div>
        </div>
      </div>
    </>
  );
}
