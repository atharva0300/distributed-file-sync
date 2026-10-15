ALTER TABLE sync_operations ADD COLUMN client_operation_id VARCHAR(255);
ALTER TABLE sync_operations ADD COLUMN upload_session_id BIGINT;

ALTER TABLE sync_operations ADD CONSTRAINT fk_sync_op_upload_session 
    FOREIGN KEY (upload_session_id) REFERENCES upload_sessions(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX idx_sync_op_client_op_id ON sync_operations(user_id, client_operation_id) WHERE client_operation_id IS NOT NULL;

-- Make operation_id unique on conflicts
ALTER TABLE conflicts ADD CONSTRAINT uk_conflict_operation_id UNIQUE (operation_id);
