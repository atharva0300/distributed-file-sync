-- Enforce the closed value sets the application already uses, so a bug or a manual write
-- cannot persist an invalid state that the state machines do not handle.
ALTER TABLE devices
    ADD CONSTRAINT chk_devices_status CHECK (status IN ('ONLINE', 'OFFLINE')),
    ADD CONSTRAINT chk_devices_type CHECK (device_type IN ('LAPTOP', 'DESKTOP', 'PHONE', 'TABLET', 'OTHER'));

ALTER TABLE upload_sessions
    ADD CONSTRAINT chk_upload_sessions_status
    CHECK (status IN ('INITIATED', 'UPLOADING', 'FINALIZING', 'COMPLETED', 'ABORTED', 'EXPIRED'));

ALTER TABLE upload_session_chunks
    ADD CONSTRAINT chk_upload_session_chunks_status CHECK (status IN ('UPLOADED'));

ALTER TABLE sync_operations
    ADD CONSTRAINT chk_sync_operations_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'CONFLICT', 'FAILED')),
    ADD CONSTRAINT chk_sync_operations_type
    CHECK (operation_type IN ('UPLOAD', 'DELETE', 'RENAME', 'MOVE'));

ALTER TABLE conflicts
    ADD CONSTRAINT chk_conflicts_status CHECK (status IN ('OPEN', 'RESOLVED')),
    ADD CONSTRAINT chk_conflicts_resolution
    CHECK (resolution IS NULL OR resolution IN ('KEEP_SERVER', 'KEEP_LOCAL', 'CREATE_CONFLICT_COPY'));

ALTER TABLE chunks
    ADD CONSTRAINT chk_chunks_status CHECK (status IN ('STAGING', 'ACTIVE', 'DELETING'));
