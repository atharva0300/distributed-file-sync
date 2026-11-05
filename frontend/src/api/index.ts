import api from './client';
import type {
  ApiResponse, AuthResponse, DeviceResponse, FileResponse,
  FileVersion, SyncOperationResponse, ConflictResponse, SyncLogResponse, DeltaResponse
} from '../types';

// ── Auth ────────────────────────────────────────────────────────────────────
export const authApi = {
  register: (email: string, password: string) =>
    api.post<ApiResponse<AuthResponse>>('/auth/register', { email, password }),
  login: (email: string, password: string) =>
    api.post<ApiResponse<AuthResponse>>('/auth/login', { email, password }),
};

// ── Devices ─────────────────────────────────────────────────────────────────
export const devicesApi = {
  list: () => api.get<ApiResponse<DeviceResponse[]>>('/devices'),
  register: (deviceName: string, deviceType: string) =>
    api.post<ApiResponse<DeviceResponse>>('/devices', { deviceName, deviceType }),
  updateStatus: (deviceId: number, status: 'ONLINE' | 'OFFLINE') =>
    api.put<ApiResponse<DeviceResponse>>(`/devices/${deviceId}/status?status=${status}`),
  delete: (deviceId: number) => api.delete<ApiResponse<void>>(`/devices/${deviceId}`),
};

// ── Files ───────────────────────────────────────────────────────────────────
export const filesApi = {
  list: (parentId?: number) =>
    api.get<ApiResponse<FileResponse[]>>('/files', { params: { parentId } }),
  get: (id: number) => api.get<ApiResponse<FileResponse>>(`/files/${id}`),
  createFolder: (name: string, parentFolderId?: number) =>
    api.post<ApiResponse<FileResponse>>('/files/folder', { name, parentFolderId }),
  initUpload: (fileName: string, fileSize: number, parentFolderId?: number) =>
    api.post<ApiResponse<FileResponse>>('/files/upload/init', { fileName, fileSize, parentFolderId }),
  delete: (id: number) => api.delete<ApiResponse<void>>(`/files/${id}`),
  rename: (fileId: number, newName: string) =>
    api.post<ApiResponse<FileResponse>>('/files/rename', { fileId, newName }),
  getVersions: (id: number) => api.get<ApiResponse<FileVersion[]>>(`/files/${id}/versions`),
  restoreVersion: (id: number, versionNumber: number, deviceId: number) =>
    api.post<ApiResponse<FileVersion>>(`/files/${id}/restore/${versionNumber}?deviceId=${deviceId}`),
};

// ── Upload Sessions (Core MVP Flow) ─────────────────────────────────────────
export const uploadApi = {
  initUpload: (fileId: number, deviceId: number, expectedSize: number, expectedHash: string, baseVersion?: number) => {
    const params = new URLSearchParams({
      fileId: String(fileId),
      deviceId: String(deviceId),
      expectedSize: String(expectedSize),
      expectedHash,
    });
    if (baseVersion !== undefined) {
      params.append('baseVersion', String(baseVersion));
    }
    return api.post<ApiResponse<{ sessionId: number, chunkSize: number }>>(`/upload/init?${params.toString()}`);
  },
  // Single upload-chunk transport: binary chunk data, hash-verified server-side
  // (file-sync-service re-hashes, then relays to storage-service which re-hashes
  // again before persisting). There is deliberately no second, unverified path.
  uploadChunk: (sessionId: number, chunkIndex: number, clientHash: string, data: Blob) => {
    const formData = new FormData();
    formData.append('data', data);
    return api.post<ApiResponse<void>>(
      `/upload/${sessionId}/chunks/${chunkIndex}?clientHash=${encodeURIComponent(clientHash)}`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    );
  },
  // Links an index to a chunk the server already has (dedup / unchanged-from-previous-version),
  // without re-sending bytes. The server verifies the chunk actually exists and is ACTIVE —
  // it never trusts a bare client-asserted hash/size.
  linkExistingChunk: (sessionId: number, chunkIndex: number, clientHash: string, size: number) =>
    api.post<ApiResponse<void>>(`/upload/${sessionId}/chunks/${chunkIndex}/link?clientHash=${encodeURIComponent(clientHash)}&size=${size}`),
  // CDC delta identity is by chunk hash, not index — the server returns the list
  // of hashes it doesn't have yet. sessionId is required: it's how the server
  // records totalChunks on the session (completeUpload needs it for CDC).
  computeDelta: (
    fileId: number,
    clientChunks: Array<{ chunkIndex: number; chunkHash: string; chunkSize: number }>,
    baseVersion: number | undefined,
    sessionId: number
  ) =>
    api.post<ApiResponse<string[]>>(`/upload/delta?fileId=${fileId}`, { clientChunks, baseVersion, sessionId }),
  getMissingChunks: (sessionId: number) =>
    api.get<ApiResponse<number[]>>(`/upload/${sessionId}/missing`),
  getConfig: () =>
    api.get<ApiResponse<{ chunkingMode: string; targetChunkSize: number; minChunkSize: number; maxChunkSize: number; chunkSize: number }>>('/upload/config'),
};

// ── Sync ────────────────────────────────────────────────────────────────────
export const syncApi = {
  getOperations: (status?: string) =>
    api.get<ApiResponse<SyncOperationResponse[]>>('/sync/operations', { params: { status } }),
  queueOperation: (
    deviceId: number,
    fileId: number | null,
    operation: string,
    baseVersion?: number,
    clientOperationId?: string,
    uploadSessionId?: number,
  ) =>
    api.post<ApiResponse<SyncOperationResponse>>('/sync/operations', {
      deviceId,
      fileId,
      operation,
      baseVersion,
      clientOperationId,
      uploadSessionId,
    }),
  getOperation: (id: number) => api.get<ApiResponse<SyncOperationResponse>>(`/sync/operations/${id}`),
  getLogs: () => api.get<ApiResponse<SyncLogResponse[]>>('/sync/logs'),
};

// ── Conflicts ───────────────────────────────────────────────────────────────
export const conflictsApi = {
  list: () => api.get<ApiResponse<ConflictResponse[]>>('/conflicts'),
  resolve: (operationId: number, strategy: 'KEEP_SERVER' | 'KEEP_LOCAL' | 'CREATE_CONFLICT_COPY') =>
    api.post<ApiResponse<string>>(`/conflicts/${operationId}/resolve`, { strategy }),
};
