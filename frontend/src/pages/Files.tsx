import React, { useEffect, useState, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { get, set, del } from 'idb-keyval';
import { filesApi, uploadApi, syncApi, devicesApi, sha256 } from '../api';
import { computeCdcBoundaries } from '../lib/cdc';
import type { FileResponse, DeviceResponse } from '../types';

// ── Stable UUID generator ─────────────────────────────────────────────────────
function generateUUID(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// ── IndexedDB session state ───────────────────────────────────────────────────
interface UploadSessionState {
  sessionId: number;
  fileId: number;
  fileHash: string;
  baseVersion: number | undefined;
  deviceId: number;
  clientOperationId: string;
  chunkSize: number;
}

export default function Files() {
  const navigate = useNavigate();
  const [files, setFiles] = useState<FileResponse[]>([]);
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [parentId, setParentId] = useState<number | undefined>(undefined);
  const [breadcrumbs, setBreadcrumbs] = useState<{ id: number | undefined; name: string }[]>([
    { id: undefined, name: 'Root' },
  ]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [error, setError] = useState('');
  const [newFolderName, setNewFolderName] = useState('');
  const [showNewFolder, setShowNewFolder] = useState(false);
  const [selectedDeviceId, setSelectedDeviceId] = useState<number | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadFiles = useCallback(() => {
    setLoading(true);
    filesApi.list(parentId)
      .then(r => setFiles(r.data.data))
      .catch(e => setError(e.response?.data?.message || 'Failed to load files'))
      .finally(() => setLoading(false));
  }, [parentId]);

  useEffect(() => { loadFiles(); }, [loadFiles]);

  useEffect(() => {
    devicesApi.list().then(r => {
      setDevices(r.data.data);
      if (r.data.data.length > 0 && !selectedDeviceId) {
        setSelectedDeviceId(r.data.data[0].id);
      }
    });
  }, []);

  const navigate_into = (folder: FileResponse) => {
    setParentId(folder.id);
    setBreadcrumbs(prev => [...prev, { id: folder.id, name: folder.name }]);
  };

  const navigate_to = (idx: number) => {
    const crumb = breadcrumbs[idx];
    setParentId(crumb.id);
    setBreadcrumbs(prev => prev.slice(0, idx + 1));
  };

  const createFolder = async () => {
    if (!newFolderName.trim()) return;
    try {
      await filesApi.createFolder(newFolderName.trim(), parentId);
      setNewFolderName('');
      setShowNewFolder(false);
      loadFiles();
    } catch (e: any) {
      setError(e.response?.data?.message || 'Failed to create folder');
    }
  };

  /**
   * Content-Defined Chunking (CDC) resumable upload flow:
   *
   * 1. Fetch authoritative CDC bounds (min/target/max chunk size) from /upload/config
   * 2. Read the file and compute content-defined chunk boundaries (same Rabin
   *    rolling-hash algorithm as the server — see lib/cdc.ts) and the full-file hash
   * 3. Determine fileId and baseVersion (new file vs. update to an existing one)
   * 4. Resume a prior session for this file+hash from IndexedDB, or create a new one
   * 5. Compute delta against the session (server tells us which chunk hashes it's
   *    missing; everything else already exists server-side and just needs linking)
   * 6. Upload missing chunks' bytes; link everything else without re-sending bytes
   * 7. Complete the upload session
   * 8. Queue a SyncOperation linking the session to the file (stable clientOperationId)
   */
  const uploadFile = async (file: File) => {
    if (!selectedDeviceId) { setError('Select a device first'); return; }
    setUploading(true);
    setUploadProgress(0);
    setError('');

    try {
      // 1. Fetch authoritative CDC bounds from server — the client MUST chunk with
      //    exactly these bounds or its chunk hashes won't line up with the server's.
      const configRes = await uploadApi.getConfig();
      const { minChunkSize, targetChunkSize, maxChunkSize } = configRes.data.data;

      // 2. Read the whole file and compute CDC boundaries + the full-file hash.
      //    (For very large files this should move to a Web Worker; kept simple here.)
      setUploadProgress(1);
      const fileBytes = new Uint8Array(await file.arrayBuffer());
      const fileHash = await sha256(fileBytes.buffer as ArrayBuffer);
      const boundaries = fileBytes.length === 0
        ? []
        : computeCdcBoundaries(fileBytes, minChunkSize, targetChunkSize, maxChunkSize);
      setUploadProgress(5);

      const clientChunks: { chunkIndex: number; chunkHash: string; chunkSize: number; offset: number }[] = [];
      for (const b of boundaries) {
        const slice = fileBytes.subarray(b.offset, b.offset + b.size);
        const hash = await sha256(slice.buffer.slice(slice.byteOffset, slice.byteOffset + slice.byteLength));
        clientChunks.push({ chunkIndex: b.index, chunkHash: hash, chunkSize: b.size, offset: b.offset });
      }
      setUploadProgress(10);

      // 3. Determine if this is a new upload or an update to an existing file
      const existingFile = files.find(f => f.name === file.name && !f.isFolder);
      let fileId: number;
      let baseVersion: number | undefined;

      if (existingFile) {
        fileId = existingFile.id;
        baseVersion = existingFile.currentVersion;
      } else {
        const initRes = await filesApi.initUpload(file.name, file.size, parentId);
        fileId = initRes.data.data.id;
        baseVersion = undefined; // new file starts at v0
      }

      // 4. Resume or create the upload session
      const stateKey = `upload_${fileId}_${fileHash}`;
      let savedState: UploadSessionState | undefined = await get(stateKey);
      let sessionId: number;
      let clientOperationId: string;

      if (
        savedState &&
        savedState.fileId === fileId &&
        savedState.deviceId === selectedDeviceId &&
        savedState.baseVersion === baseVersion
      ) {
        sessionId = savedState.sessionId;
        clientOperationId = savedState.clientOperationId;
      } else {
        const sessionRes = await uploadApi.initUpload(fileId, selectedDeviceId, file.size, fileHash, baseVersion);
        sessionId = sessionRes.data.data.sessionId;
        clientOperationId = generateUUID(); // stable per upload attempt, stored in IDB
        savedState = {
          sessionId,
          fileId,
          fileHash,
          baseVersion,
          deviceId: selectedDeviceId,
          clientOperationId,
          chunkSize: targetChunkSize,
        };
        await set(stateKey, savedState);
      }

      // 5. Compute delta against the session. This both tells us which chunk
      //    hashes the server is missing AND records totalChunks on the session
      //    (completeUpload requires that to be set for CDC sessions).
      const deltaRes = await uploadApi.computeDelta(
        fileId,
        clientChunks.map(({ chunkIndex, chunkHash, chunkSize }) => ({ chunkIndex, chunkHash, chunkSize })),
        baseVersion,
        sessionId,
      );
      const missingHashes = new Set(deltaRes.data.data);

      // Indices already uploaded in a resumed session don't need to be touched again.
      let alreadyDoneIndices = new Set<number>();
      try {
        const missingRes = await uploadApi.getMissingChunks(sessionId);
        alreadyDoneIndices = new Set(clientChunks.map(c => c.chunkIndex).filter(i => !missingRes.data.data.includes(i)));
      } catch {
        // Session may be brand new / not yet have totalChunks — nothing done yet.
      }

      const toUpload = clientChunks.filter(c => missingHashes.has(c.chunkHash) && !alreadyDoneIndices.has(c.chunkIndex));
      const toLink = clientChunks.filter(c => !missingHashes.has(c.chunkHash) && !alreadyDoneIndices.has(c.chunkIndex));

      // 6a. Link chunks the server already has — no bytes sent.
      for (const c of toLink) {
        await uploadApi.linkExistingChunk(sessionId, c.chunkIndex, c.chunkHash, c.chunkSize);
      }
      setUploadProgress(15);

      // 6b. Upload missing chunks' bytes with bounded concurrency (4 parallel workers).
      if (toUpload.length > 0) {
        let uploadedCount = 0;
        const CONCURRENCY_LIMIT = 4;
        const uploadQueue = [...toUpload];

        const uploadWorker = async () => {
          while (uploadQueue.length > 0) {
            const c = uploadQueue.shift();
            if (!c) break;
            const chunkBlob = file.slice(c.offset, c.offset + c.chunkSize);
            await uploadApi.uploadChunk(sessionId, c.chunkIndex, c.chunkHash, chunkBlob);
            uploadedCount++;
            setUploadProgress(15 + Math.round((uploadedCount / toUpload.length) * 75));
          }
        };

        const workers = Array(Math.min(CONCURRENCY_LIMIT, toUpload.length))
          .fill(null)
          .map(() => uploadWorker());
        await Promise.all(workers);
      }

      setUploadProgress(90);

      // 7. Complete the upload session (triggers full-hash verification on server)
      await uploadApi.completeUpload(sessionId);
      setUploadProgress(95);

      // 8. Queue a SyncOperation that links this session to the file
      //    clientOperationId is stable — if the network fails here, retrying is idempotent
      await syncApi.queueOperation(
        selectedDeviceId,
        fileId,
        'UPLOAD',
        baseVersion,
        clientOperationId,
        sessionId,
      );

      // 9. Clean up IDB state and refresh
      await del(stateKey);
      setUploadProgress(100);
      loadFiles();
    } catch (e: any) {
      const msg = e.response?.data?.message || e.response?.data?.error || e.message || 'Upload failed';
      setError(msg);
    } finally {
      setUploading(false);
      setUploadProgress(0);
    }
  };

  const deleteFile = async (id: number) => {
    if (!confirm('Delete this file?')) return;
    try {
      await filesApi.delete(id);
      loadFiles();
    } catch (e: any) {
      setError(e.response?.data?.message || 'Delete failed');
    }
  };

  const downloadFile = async (file: FileResponse) => {
    const res = await filesApi.download(file.id);
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement('a');
    a.href = url; a.download = file.name; a.click();
    URL.revokeObjectURL(url);
  };

  const formatSize = (bytes: number | null) => {
    if (!bytes) return '—';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  };

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Files</h1>
        <div className="gap-2 align-center">
          <select className="form-select" style={{ width: 160 }}
            value={selectedDeviceId ?? ''} onChange={e => setSelectedDeviceId(Number(e.target.value))}>
            <option value="">Select device</option>
            {devices.map(d => <option key={d.id} value={d.id}>{d.deviceName}</option>)}
          </select>
          <button className="btn btn-outline btn-sm" onClick={() => setShowNewFolder(!showNewFolder)}>📁 New Folder</button>
          <button className="btn btn-primary btn-sm" onClick={() => fileInputRef.current?.click()} disabled={uploading}>
            {uploading ? `Uploading ${uploadProgress}%` : '⬆ Upload File'}
          </button>
          <input ref={fileInputRef} type="file" style={{ display: 'none' }}
            onChange={e => { if (e.target.files?.[0]) uploadFile(e.target.files[0]); e.target.value = ''; }} />
        </div>
      </div>
      <div className="page-body">
        {error && <div className="alert alert-error">{error}</div>}

        {/* Upload progress */}
        {uploading && (
          <div className="mb-3">
            <div style={{ marginBottom: 6, fontSize: 13 }}>Uploading chunks… {uploadProgress}%</div>
            <div className="progress-bar"><div className="progress-fill" style={{ width: `${uploadProgress}%` }} /></div>
          </div>
        )}

        {/* New folder form */}
        {showNewFolder && (
          <div className="card mb-3" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input className="form-input flex-1" placeholder="Folder name" value={newFolderName}
              onChange={e => setNewFolderName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && createFolder()} />
            <button className="btn btn-primary btn-sm" onClick={createFolder}>Create</button>
            <button className="btn btn-outline btn-sm" onClick={() => setShowNewFolder(false)}>Cancel</button>
          </div>
        )}

        {/* Breadcrumbs */}
        <div className="gap-2 align-center mb-3" style={{ flexWrap: 'wrap' }}>
          {breadcrumbs.map((crumb, i) => (
            <span key={i} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              {i < breadcrumbs.length - 1
                ? <button className="btn btn-outline btn-xs" onClick={() => navigate_to(i)}>{crumb.name}</button>
                : <span style={{ fontWeight: 600 }}>{crumb.name}</span>}
              {i < breadcrumbs.length - 1 && <span className="text-muted">/</span>}
            </span>
          ))}
        </div>

        {/* File table */}
        <div className="card" style={{ padding: 0 }}>
          {loading ? (
            <div style={{ padding: 24 }} className="text-muted">Loading files…</div>
          ) : files.length === 0 ? (
            <div style={{ padding: 24 }} className="text-muted">
              This folder is empty. Upload a file or create a folder.
            </div>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Size</th>
                    <th>Version</th>
                    <th>Status</th>
                    <th>Modified</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {files.sort((a, b) => (b.isFolder ? 1 : 0) - (a.isFolder ? 1 : 0)).map(f => (
                    <tr key={f.id}>
                      <td>
                        <div className="gap-2 align-center">
                          <span className="file-icon">{f.isFolder ? '📁' : '📄'}</span>
                          {f.isFolder
                            ? <button className="btn btn-outline btn-xs" onClick={() => navigate_into(f)}>{f.name}</button>
                            : <span>{f.name}</span>}
                        </div>
                      </td>
                      <td className="text-muted">{formatSize(f.fileSize)}</td>
                      <td><span className="badge badge-syncing" style={{ fontSize: 11 }}>v{f.currentVersion}</span></td>
                      <td><span className={`badge badge-${f.syncStatus?.toLowerCase()}`}>{f.syncStatus}</span></td>
                      <td className="mono">{new Date(f.updatedAt).toLocaleDateString()}</td>
                      <td>
                        <div className="gap-2">
                          {!f.isFolder && (
                            <>
                              <button className="btn btn-outline btn-xs" onClick={() => downloadFile(f)}>⬇</button>
                              <button className="btn btn-outline btn-xs" onClick={() => navigate(`/files/${f.id}/versions`)}>
                                History
                              </button>
                            </>
                          )}
                          <button className="btn btn-danger btn-xs" onClick={() => deleteFile(f.id)}>✕</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
