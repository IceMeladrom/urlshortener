-- seed.sql
-- Usage: psql -U postgres -d shortener -v num_rows="50000" -f - < seed.sql
-- Вывод: в STDOUT будут только short_code, вставленные в этом прогона (по одному в строке).

-- Опционально очистить таблицу перед экспериментом (раскомментируйте при необходимости)
TRUNCATE TABLE links RESTART IDENTITY;
ALTER SEQUENCE IF EXISTS seed_autoinc RESTART WITH 1;

CREATE TABLE IF NOT EXISTS links (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(32) NOT NULL UNIQUE,
    original_url TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    click_count BIGINT DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_original_url ON links(original_url);
CREATE INDEX IF NOT EXISTS idx_short_code ON links(short_code);
CREATE INDEX IF NOT EXISTS idx_expires_at ON links(expires_at);

-- последовательность для уникальных чисел (один вызов nextval на вставляемую запись)
CREATE SEQUENCE IF NOT EXISTS seed_autoinc;

-- Генерация: вызываем nextval ровно один раз на запись (через seqs),
-- формируем short_code на основе seqval и вставляем.
-- Затем COPY выводит ТОЛЬКО short_code, которые реально были вставлены в этом прогона.
COPY (
  WITH
  seqs AS (
    SELECT nextval('seed_autoinc') AS seqval
    FROM generate_series(1, :num_rows)
  ),
  gen AS (
    SELECT
      seqval,
      'tst' || LPAD(seqval::text, 6, '0') AS short_code_candidate,
      'https://example.test/perf/' || substring(md5((seqval::text || clock_timestamp()::text)::text),1,16) AS original_url,
      now() AS created_at,
      now() + interval '7 days' AS expires_at,
      0::bigint AS click_count
    FROM seqs
  ),
  ins AS (
    INSERT INTO links (short_code, original_url, created_at, expires_at, click_count)
    SELECT short_code_candidate, original_url, created_at, expires_at, click_count
    FROM gen
    ON CONFLICT (short_code) DO NOTHING
    RETURNING short_code
  )
  SELECT short_code FROM ins
) TO STDOUT;