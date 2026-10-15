-- V13: Add total_chunks to upload_sessions for CDC resumable upload support
--
-- With fixed-size chunking, the total chunk count is always derivable from:
--   ceil(expectedFileSize / chunkSize)
-- so it was never stored explicitly.
--
-- With CDC, chunk boundaries depend on content, not file size. The total chunk count
-- is only known after the client runs CDC locally and sends the manifest via
-- POST /api/upload/delta. We store it here so the /missing endpoint can correctly
-- report which of [0..totalChunks-1] have not yet been uploaded.
--
-- NULL means "not yet known" (delta not yet computed for this session).

ALTER TABLE upload_sessions
    ADD COLUMN total_chunks INTEGER;
