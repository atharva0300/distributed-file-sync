ALTER TABLE upload_session_chunks ADD COLUMN chunk_id BIGINT;

UPDATE upload_session_chunks usc
SET chunk_id = c.id
FROM chunks c
WHERE usc.chunk_hash = c.hash;

DELETE FROM upload_sessions WHERE id IN (
    SELECT upload_session_id FROM upload_session_chunks WHERE chunk_id IS NULL
);

ALTER TABLE upload_session_chunks ALTER COLUMN chunk_id SET NOT NULL;
ALTER TABLE upload_session_chunks ADD CONSTRAINT fk_usc_chunk_id FOREIGN KEY (chunk_id) REFERENCES chunks(id) ON DELETE RESTRICT;

ALTER TABLE upload_session_chunks DROP COLUMN chunk_hash;
