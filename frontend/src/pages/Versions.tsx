import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { filesApi, devicesApi } from '../api';
import type { FileVersion, DeviceResponse, FileResponse } from '../types';

export default function Versions() {
  const { fileId } = useParams<{ fileId: string }>();
  const navigate = useNavigate();
  const [versions, setVersions] = useState<FileVersion[]>([]);
  const [file, setFile] = useState<FileResponse | null>(null);
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [restoring, setRestoring] = useState<number | null>(null);
  const [success, setSuccess] = useState('');

  useEffect(() => {
    if (!fileId) return;
    const id = Number(fileId);
    Promise.all([filesApi.getVersions(id), filesApi.get(id), devicesApi.list()])
      .then(([v, f, d]) => {
        setVersions(v.data.data);
        setFile(f.data.data);
        setDevices(d.data.data);
        if (d.data.data.length > 0) setSelectedDeviceId(d.data.data[0].id);
      })
      .finally(() => setLoading(false));
  }, [fileId]);

  const restore = async (versionNumber: number) => {
    if (!selectedDeviceId || !fileId) return;
    setRestoring(versionNumber);
    try {
      await filesApi.restoreVersion(Number(fileId), versionNumber, selectedDeviceId);
      setSuccess(`Successfully restored to version ${versionNumber}. A new version was created.`);
      const v = await filesApi.getVersions(Number(fileId));
      setVersions(v.data.data);
    } catch (e: any) {
      alert(e.response?.data?.message || 'Restore failed');
    } finally {
      setRestoring(null);
    }
  };

  const formatSize = (bytes: number | null) => {
    if (!bytes) return '—';
    return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  };

  if (loading) return <div className="page-body text-muted">Loading…</div>;

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">
          <button className="btn btn-outline btn-sm" onClick={() => navigate('/files')} style={{ marginRight: 12 }}>← Back</button>
          Version History: {file?.name}
        </h1>
        <select className="form-select" style={{ width: 160 }}
          value={selectedDeviceId ?? ''} onChange={e => setSelectedDeviceId(Number(e.target.value))}>
          {devices.map(d => <option key={d.id} value={d.id}>{d.deviceName}</option>)}
        </select>
      </div>
      <div className="page-body">
        {success && <div className="alert alert-success">{success}</div>}

        {/* Current version info */}
        {file && (
          <div className="card mb-4 gap-2" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div><span className="text-muted">Current version: </span><strong>v{file.currentVersion}</strong></div>
            <div><span className="text-muted">Size: </span>{formatSize(file.fileSize)}</div>
            <div><span className="text-muted">Hash: </span><span className="mono">{file.fileHash || '—'}</span></div>
          </div>
        )}

        {/* Version timeline */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {versions.map((v, i) => (
            <div key={v.id} className="card" style={{
              borderLeft: `3px solid ${i === 0 ? 'var(--accent-blue)' : 'var(--border)'}`,
              opacity: i === 0 ? 1 : 0.85
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div className="gap-2 align-center mb-3">
                    <span style={{ fontWeight: 700, fontSize: 16 }}>v{v.versionNumber}</span>
                    {i === 0 && <span className="badge badge-syncing">Current</span>}
                    <span className="badge badge-pending">{v.changeType}</span>
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'flex', flexDirection: 'column', gap: 4 }}>
                    <div>📅 {new Date(v.createdAt).toLocaleString()}</div>
                    <div>💻 {v.createdByDeviceName || 'Unknown device'}</div>
                    <div>📦 {v.chunks?.length || 0} chunks · {formatSize(v.fileSize)}</div>
                    <div className="mono" style={{ marginTop: 4 }}>{v.fileHash?.slice(0, 32)}…</div>
                  </div>
                </div>
                {i !== 0 && (
                  <button
                    className="btn btn-outline btn-sm"
                    onClick={() => restore(v.versionNumber)}
                    disabled={restoring === v.versionNumber}>
                    {restoring === v.versionNumber ? 'Restoring…' : '↩ Restore'}
                  </button>
                )}
              </div>

              {/* Chunk list */}
              {v.chunks && v.chunks.length > 0 && (
                <details style={{ marginTop: 12 }}>
                  <summary className="text-muted" style={{ cursor: 'pointer', fontSize: 12 }}>
                    Show {v.chunks.length} chunks
                  </summary>
                  <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
                    {v.chunks.slice(0, 10).map(c => (
                      <div key={c.chunkIndex} style={{ fontSize: 11, display: 'flex', gap: 12 }} className="mono">
                        <span>#{c.chunkIndex}</span>
                        <span>{c.chunkHash.slice(0, 16)}…</span>
                        <span className="text-muted">{(c.chunkSize / 1024).toFixed(1)} KB</span>
                      </div>
                    ))}
                    {v.chunks.length > 10 && <div className="text-muted" style={{ fontSize: 11 }}>…and {v.chunks.length - 10} more</div>}
                  </div>
                </details>
              )}
            </div>
          ))}
        </div>

        {versions.length === 0 && (
          <div className="text-muted">No versions yet. Upload a file first.</div>
        )}
      </div>
    </>
  );
}
