-- Add upload_session_id to file_versions and make it unique
ALTER TABLE file_versions 
ADD COLUMN upload_session_id BIGINT;

ALTER TABLE file_versions 
ADD CONSTRAINT fk_file_versions_upload_session 
FOREIGN KEY (upload_session_id) 
REFERENCES upload_sessions(id) ON DELETE SET NULL;

ALTER TABLE file_versions
ADD CONSTRAINT uq_file_versions_upload_session_id
UNIQUE (upload_session_id);
