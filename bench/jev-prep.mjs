// Build a Jev (TypeSafe) pre-filter benchmark set from a prod news.db snapshot.
// Ground truth = Step 1 decisions already made by the LLM: `rejected_events.status='rejected'`
// vs `covered_events` (shortlisted new/meaningful_update). Duplicates are excluded — Jev
// cannot dedup, the experiment only asks "can a cheap decision model drop junk before Step 1".
// usage: node bench/jev-prep.mjs <out.json> [db-path] [perClassPerCategory] [--archive <dir>]   (from repo root)
//   --archive merges the retention-archive files (archive.enabled) into the history; move SINCE back to use it.
import { DatabaseSync } from 'node:sqlite';
import { writeFileSync } from 'node:fs';
import { mergeArchive, takeArchiveFlag } from './lib/archive.mjs';

const archiveDir = takeArchiveFlag(process.argv);
const [,, outPath, dbPath = 'logs/news.db', perClassArg = '250'] = process.argv;
const PER_CLASS = Number(perClassArg);
const SINCE = '2026-08-03';           // both tables have data from this date in the snapshot
const CATEGORIES = ['gaming', 'tech', 'science'];
const MAX_CHARS = 1000;               // Article.promptText() default

const db = new DatabaseSync(dbPath, { readOnly: true });
if (archiveDir) {
  const n = mergeArchive(db, archiveDir, ['articles', 'covered_events', 'rejected_events']);
  console.error(`merged ${n} archive file(s) from ${archiveDir}`);
}

// mirrors HtmlText.strip well enough for a benchmark: drop tags, decode common entities, squash ws
function stripHtml(s) {
  return String(s ?? '')
    .replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<br\s*\/?>|<\/p>|<\/div>|<\/li>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(n))
    .replace(/[ \t]+/g, ' ').replace(/\s*\n\s*/g, '\n').trim();
}
const promptText = (a) => ((a.summary && a.summary.trim()) ? a.summary.trim() : stripHtml(a.description)).slice(0, MAX_CHARS);
const host = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return ''; } };

// deterministic pseudo-random order so re-running prep yields the same set
const ORDER = 'ORDER BY (e.id * 2654435761) % 1000003';

const rejectedSql = `
  SELECT e.id AS eid, e.category, e.event_type, e.newsworthiness, e.digest_fit, e.url, e.extracted_at AS decided_at,
         a.id AS article_id, a.title, a.description, a.summary, a.pub_date
  FROM rejected_events e JOIN articles a ON a.link = e.url
  WHERE e.category = ? AND e.status = 'rejected' AND e.extracted_at > ?
    AND length(a.title) > 10 AND length(coalesce(a.summary, a.description)) > 80
    AND e.url NOT IN (SELECT url FROM covered_events WHERE category = e.category)
  ${ORDER} LIMIT ?`;
const coveredSql = `
  SELECT e.id AS eid, e.category, e.event_type, e.newsworthiness, e.digest_fit, e.url, e.covered_at AS decided_at,
         a.id AS article_id, a.title, a.description, a.summary, a.pub_date
  FROM covered_events e JOIN articles a ON a.link = e.url
  WHERE e.category = ? AND e.covered_at > ?
    AND length(a.title) > 10 AND length(coalesce(a.summary, a.description)) > 80
  ${ORDER} LIMIT ?`;

const items = [];
for (const cat of CATEGORIES) {
  for (const [label, sql] of [['rejected', rejectedSql], ['covered', coveredSql]]) {
    const rows = db.prepare(sql).all(cat, SINCE, PER_CLASS);
    for (const r of rows) {
      items.push({
        id: `${cat}-${label}-${r.eid}`,
        category: cat,
        label,                                   // ground truth: Step 1 verdict
        step1: { eventType: r.event_type, newsworthiness: r.newsworthiness, digestFit: r.digest_fit, decidedAt: r.decided_at },
        articleId: r.article_id,
        title: r.title,
        url: r.url,
        source: host(r.url),
        pubDate: r.pub_date,
        text: promptText(r),
        usedSummary: !!(r.summary && r.summary.trim()),
      });
    }
    console.log(`${cat}/${label}: ${rows.length}`);
  }
}
writeFileSync(outPath, JSON.stringify({ builtAt: new Date().toISOString(), db: dbPath, since: SINCE, perClass: PER_CLASS, maxChars: MAX_CHARS, items }, null, 1));
console.log(`wrote ${items.length} items → ${outPath}`);
