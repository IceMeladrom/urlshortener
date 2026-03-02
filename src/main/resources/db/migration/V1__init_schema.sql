CREATE TABLE IF NOT EXISTS links (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(16) NOT NULL UNIQUE,
    original_url TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    click_count BIGINT DEFAULT 0
);

-- уникальность оригинального URL обеспечивает идемпотентность создания короткой ссылки
CREATE UNIQUE INDEX IF NOT EXISTS uq_original_url ON links(original_url);

CREATE INDEX IF NOT EXISTS idx_short_code ON links(short_code);
CREATE INDEX IF NOT EXISTS idx_expires_at ON links(expires_at);