-- V9: Chunk STAGING lifecycle support
--
-- STAGING status enables safe deduplication:
--   1. INSERT chunk row with status=STAGING (DB unique constraint ensures only one succeeds)
--   2. Upload to S3
--   3. Transition STAGING → ACTIVE in same transaction as UploadSessionChunk creation
--
-- If S3 upload fails, the chunk stays STAGING and is cleaned up by the GC after the grace period.
-- This means orphaned S3 objects (failed between S3 and DB) are handled by S3 lifecycle rules,
-- and orphaned DB rows (failed before S3) are handled by the STAGING GC job.

-- Allow STAGING as a valid status value (already handled by CHECK constraint if present)
-- Index for STAGING GC cleanup (find stale STAGING rows efficiently)
CREATE INDEX IF NOT EXISTS idx_chunks_staging_gc
    ON chunks(created_at)
    WHERE status = 'STAGING';

-- Index for ACTIVE orphan detection (existing GC query uses status = 'ACTIVE')
CREATE INDEX IF NOT EXISTS idx_chunks_active_created
    ON chunks(created_at)
    WHERE status = 'ACTIVE';
