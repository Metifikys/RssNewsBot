-- One-off cleanup after the 2026-07-13 20:41 restart flood.
-- BUG-020 link normalization went live without migrating existing rows, so
-- findExistingLinks stopped matching stored (unnormalized) links and ~1070
-- already-seen articles were re-ingested as "new". The resulting 100-article
-- Step 1 prompts (~141KB) no longer fit the 15-minute category deadline and
-- the gaming queue livelocked.
--
-- Run on the server with the bot STOPPED:
--   sqlite3 news.db < cleanup_20260714_flood.sql
-- Designed to be run on 2026-07-14; the pub_date cutoff below is absolute.

BEGIN;

-- 1) Queued rows that are normalization twins of an already-stored article:
--    same link modulo trailing slash, ?tracking-params, or #fragment.
--    Mark DUPLICATE and point duplicate_of at the oldest original.
UPDATE articles
SET status = 'DUPLICATE',
    processing_started_at = NULL,
    duplicate_of = (
        SELECT MIN(o.id) FROM articles o
        WHERE o.id < articles.id
          AND (o.link = articles.link || '/'
               OR (o.link >= articles.link || '?'  AND o.link < articles.link || '@')
               OR (o.link >= articles.link || '/?' AND o.link < articles.link || '/@')
               OR (o.link >= articles.link || '#'  AND o.link < articles.link || '$'))
    )
WHERE id >= 2033726
  AND status IN ('UNPROCESSED', 'PROCESSING')
  AND EXISTS (
        SELECT 1 FROM articles o
        WHERE o.id < articles.id
          AND (o.link = articles.link || '/'
               OR (o.link >= articles.link || '?'  AND o.link < articles.link || '@')
               OR (o.link >= articles.link || '/?' AND o.link < articles.link || '/@')
               OR (o.link >= articles.link || '#'  AND o.link < articles.link || '$'))
  );

-- 2) Flush the rest of the flood backlog: anything still queued with a pub_date
--    before 2026-07-14 is re-ingested old stories (deep feed windows) or
--    yesterday-evening news that never made it out and is stale now. Close them
--    out so the next cycle starts with a queue that fits the category deadline.
UPDATE articles
SET status = 'PROCESSED',
    processing_started_at = NULL
WHERE status IN ('UNPROCESSED', 'PROCESSING')
  AND pub_date < '2026-07-14';

COMMIT;

-- Optional (separate legacy junk, not part of the flood): 1021 tech rows with
-- literal status '0', ids 1937432-1938549, pub_date 2022..2026-05. Inert but
-- noise. Uncomment to remove:
-- DELETE FROM articles WHERE status = '0';
