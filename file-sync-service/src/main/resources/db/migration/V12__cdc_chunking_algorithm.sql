-- V12: Content-Defined Chunking (CDC) — backward-compatible schema extension
--
-- Adds a 'chunking_algorithm' column to file_versions and upload_sessions to record
-- which chunking strategy was used to produce each version's chunks.
--
-- Values:
--   'FIXED' — legacy equal-size 4 MB chunks (boundary-shift problem)
--   'CDC'   — content-defined chunking via Rabin fingerprint rolling hash
--
-- All pre-existing rows default to 'FIXED', meaning historical versions remain
-- correctly interpreted as fixed-size. No data migration is performed because:
--   1. The chunk data itself is unchanged — only the algorithm label is new.
--   2. Every chunk already has its actual size in file_version_chunks.chunk_size,
--      so old fixed-size chunks can be reassembled correctly regardless of this column.
--   3. Migrating existing rows to CDC would be misleading — they were actually chunked
--      using the FIXED algorithm.
--
-- New versions uploaded after this migration will have chunking_algorithm = 'CDC'
-- (unless the server is configured in FIXED mode via CHUNKING_MODE=FIXED env var).

ALTER TABLE file_versions
    ADD COLUMN chunking_algorithm VARCHAR(10) NOT NULL DEFAULT 'FIXED';

ALTER TABLE upload_sessions
    ADD COLUMN chunking_algorithm VARCHAR(10) NOT NULL DEFAULT 'FIXED';

-- Index for analytics / debugging: quickly find all CDC vs FIXED versions
CREATE INDEX idx_file_versions_chunking_algo
    ON file_versions(chunking_algorithm);
