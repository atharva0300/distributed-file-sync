CREATE UNIQUE INDEX idx_file_unique_name ON files(user_id, COALESCE(parent_folder_id, -1), name) WHERE deleted = false;
CREATE INDEX idx_file_version_chunks_chunk_id ON file_version_chunks(chunk_id);
CREATE INDEX idx_upload_session_chunks ON upload_session_chunks(chunk_index);
CREATE INDEX idx_sync_operations_status_locked ON sync_operations(status, locked_at);
