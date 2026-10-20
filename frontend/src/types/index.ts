// API types matching backend DTOs

export interface AuthResponse {
  token: string;
  tokenType: string;
  userId: number;
  email: string;
}

export interface DeviceResponse {
  id: number;
  deviceName: string;
  deviceType: 'LAPTOP' | 'DESKTOP' | 'PHONE' | 'TABLET' | 'OTHER';
  deviceToken: string;
  status: 'ONLINE' | 'OFFLINE';
  lastSeenAt: string | null;
  createdAt: string;
}

export interface FileResponse {
  id: number;
  parentFolderId: number | null;
  name: string;
  path: string;
  currentVersion: number;
  fileHash: string | null;
  fileSize: number | null;
  isFolder: boolean;
  syncStatus: 'PENDING' | 'SYNCING' | 'SYNCED' | 'CONFLICT' | 'FAILED';
  uploadComplete: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ChunkRecord {
  chunkIndex: number;
  chunkHash: string;
  chunkSize: number;
  storagePath: string;
}

export interface FileVersion {
  id: string;
  fileId: number;
  versionNumber: number;
  fileHash: string;
  fileSize: number;
  chunks: ChunkRecord[];
  createdByDeviceId: number;
  createdByDeviceName: string;
  changeType: string;
  createdAt: string;
}

export interface SyncOperationResponse {
  id: number;
  deviceId: number;
  fileId: number | null;
  operation: string;
  status: 'PENDING' | 'SYNCING' | 'SYNCED' | 'CONFLICT' | 'FAILED';
  baseVersion: number | null;
  retryCount: number;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ConflictResponse {
  operationId: number;
  fileId: number;
  deviceId: number;
  clientBaseVersion: number | null;
  message: string;
  detectedAt: string;
}

export interface SyncLogResponse {
  id: string;
  fileId: number | null;
  fileName: string | null;
  operation: string;
  status: string;
  timestamp: string;
  details: string | null;
  errorMessage: string | null;
}

export interface ApiResponse<T> {
  success: boolean;
  message: string | null;
  data: T;
}

export interface DeltaResponse {
  fileId: number;
  serverVersion: number;
  missingChunkIndexes: number[];
  totalClientChunks: number;
}
