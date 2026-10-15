-- Add updated_at timestamp to chunks table to track status transitions (e.g., STAGING -> ACTIVE)
ALTER TABLE chunks
ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Create an index to quickly find old chunks by their updated_at time for GC
CREATE INDEX idx_chunks_updated_at ON chunks(updated_at);
