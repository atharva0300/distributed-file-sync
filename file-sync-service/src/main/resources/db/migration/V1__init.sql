CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE devices (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name VARCHAR(255) NOT NULL,
    device_type VARCHAR(50) NOT NULL,
    device_token_hash VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    last_seen_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE files (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_folder_id BIGINT REFERENCES files(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    is_folder BOOLEAN NOT NULL DEFAULT FALSE,
    current_version INTEGER NOT NULL DEFAULT 0,
    current_version_id BIGINT,
    file_size BIGINT NOT NULL DEFAULT 0,
    file_hash VARCHAR(255),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (user_id, parent_folder_id, name)
);

CREATE TABLE chunks (
    id BIGSERIAL PRIMARY KEY,
    hash VARCHAR(255) UNIQUE NOT NULL,
    size BIGINT NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE file_versions (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    file_hash VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    created_by_device_id BIGINT REFERENCES devices(id) ON DELETE SET NULL,
    change_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (file_id, version_number)
);

ALTER TABLE files ADD CONSTRAINT fk_files_current_version_id FOREIGN KEY (current_version_id) REFERENCES file_versions(id) ON DELETE SET NULL;

CREATE TABLE file_version_chunks (
    version_id BIGINT NOT NULL REFERENCES file_versions(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    chunk_id BIGINT NOT NULL REFERENCES chunks(id) ON DELETE RESTRICT,
    chunk_size BIGINT NOT NULL,
    PRIMARY KEY (version_id, chunk_index)
);

CREATE TABLE upload_sessions (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    device_id BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    target_version INTEGER NOT NULL,
    expected_file_size BIGINT NOT NULL,
    expected_file_hash VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE upload_session_chunks (
    id BIGSERIAL PRIMARY KEY,
    upload_session_id BIGINT NOT NULL REFERENCES upload_sessions(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    chunk_hash VARCHAR(255) NOT NULL,
    chunk_size BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (upload_session_id, chunk_index)
);

CREATE TABLE sync_operations (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_id BIGINT REFERENCES files(id) ON DELETE CASCADE,
    operation_type VARCHAR(50) NOT NULL,
    payload TEXT,
    base_version INTEGER,
    status VARCHAR(50) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP NOT NULL,
    locked_at TIMESTAMP,
    locked_by VARCHAR(255),
    last_error TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE conflicts (
    id BIGSERIAL PRIMARY KEY,
    operation_id BIGINT NOT NULL REFERENCES sync_operations(id) ON DELETE CASCADE,
    file_id BIGINT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    server_version INTEGER NOT NULL,
    client_base_version INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    resolution VARCHAR(50),
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP
);

-- Indexes
CREATE INDEX idx_files_user_parent ON files(user_id, parent_folder_id);
CREATE INDEX idx_sync_ops_status_available ON sync_operations(status, available_at);
CREATE INDEX idx_sync_ops_file_status ON sync_operations(file_id, status);
CREATE INDEX idx_devices_user ON devices(user_id);
CREATE INDEX idx_conflicts_file_status ON conflicts(file_id, status);
