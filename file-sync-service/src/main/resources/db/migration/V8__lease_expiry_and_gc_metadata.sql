-- V8: Lease expiry for proper fencing + GC metadata + performance indexes

-- Worker lease expiry: replaces age-based stale detection
ALTER TABLE sync_operations ADD COLUMN lease_expires_at TIMESTAMP;

-- Index for expiry-based stale recovery (only PROCESSING rows need this)
CREATE INDEX idx_sync_ops_lease_expires ON sync_operations(lease_expires_at) WHERE status = 'PROCESSING';

-- Chunk GC lifecycle: records when a chunk entered DELETING state for stuck-chunk recovery
ALTER TABLE chunks ADD COLUMN deleting_at TIMESTAMP;

-- Index for session expiry scheduled job
CREATE INDEX idx_upload_sessions_status_expires ON upload_sessions(status, expires_at);

-- Index for stuck FINALIZING session recovery
CREATE INDEX idx_upload_sessions_finalizing ON upload_sessions(status, updated_at) WHERE status = 'FINALIZING';

-- Fix stale index from V2: chunk_hash column was dropped in V4, this index is now invalid on PostgreSQL
-- (only drop if it exists — safe DDL)
DROP INDEX IF EXISTS idx_upload_session_chunks_hash;

-- Add index on upload_session_chunks.chunk_id (FK) for GC reference checks
CREATE INDEX IF NOT EXISTS idx_usc_chunk_id ON upload_session_chunks(chunk_id);
