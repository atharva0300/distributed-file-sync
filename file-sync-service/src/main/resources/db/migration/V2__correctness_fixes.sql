-- Drop the flawed unique constraint on files
ALTER TABLE files DROP CONSTRAINT files_user_id_parent_folder_id_name_key;

-- Add partial unique index for active files, handling NULL parent_folder_id by coalescing it to -1 for indexing purposes
CREATE UNIQUE INDEX idx_file_unique_name ON files(user_id, COALESCE(parent_folder_id, -1), name) WHERE deleted = false;

-- Add indexes for GC and orphan checks
CREATE INDEX idx_file_version_chunks_chunk_id ON file_version_chunks(chunk_id);
CREATE INDEX idx_upload_session_chunks_hash ON upload_session_chunks(chunk_hash);

-- Add base_version to upload_sessions
ALTER TABLE upload_sessions ADD COLUMN base_version INTEGER;

-- Add composite index on sync_operations for status and locking
CREATE INDEX idx_sync_operations_status_locked ON sync_operations(status, locked_at);
