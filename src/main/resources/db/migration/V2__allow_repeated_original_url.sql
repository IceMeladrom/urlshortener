DROP INDEX IF EXISTS uq_original_url;

CREATE INDEX IF NOT EXISTS idx_original_url ON links(original_url);
