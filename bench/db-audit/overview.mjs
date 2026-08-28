import { DatabaseSync } from 'node:sqlite';

// usage: node bench/db-audit/overview.mjs [db-path]   (default: logs/news.db)
const db = new DatabaseSync(process.argv[2] || 'logs/news.db', { readOnly: true });
const q = (sql, ...p) => db.prepare(sql).all(...p);

console.log('=== articles by category ===');
console.table(q(`SELECT category, COUNT(*) n, MIN(pub_date) min_pub, MAX(pub_date) max_pub FROM articles GROUP BY category ORDER BY n DESC`));

console.log('=== articles by status ===');
console.table(q(`SELECT category, status, COUNT(*) n FROM articles GROUP BY category, status ORDER BY category, n DESC`));

console.log('=== description quality (last 30 days) ===');
console.table(q(`SELECT category,
  COUNT(*) n,
  SUM(CASE WHEN length(description) = 0 THEN 1 ELSE 0 END) empty_desc,
  SUM(CASE WHEN length(description) BETWEEN 1 AND 80 THEN 1 ELSE 0 END) tiny_desc,
  SUM(CASE WHEN length(description) BETWEEN 81 AND 300 THEN 1 ELSE 0 END) short_desc,
  SUM(CASE WHEN length(description) > 300 THEN 1 ELSE 0 END) ok_desc,
  SUM(CASE WHEN summary IS NOT NULL AND length(summary) > 0 THEN 1 ELSE 0 END) has_summary
FROM articles WHERE pub_date >= datetime('now','-30 days') GROUP BY category`));

console.log('=== summaries (recent digests) ===');
console.table(q(`SELECT category, created_at, article_count, length(summary) len FROM summaries ORDER BY created_at DESC LIMIT 12`));

console.log('=== llm_calls last 14d ===');
console.table(q(`SELECT provider, model, use_case, COUNT(*) n, ROUND(AVG(duration_ms)) avg_ms, ROUND(SUM(est_cost_usd),3) cost
FROM llm_calls WHERE ts >= datetime('now','-14 days') GROUP BY provider, model, use_case ORDER BY n DESC LIMIT 20`));

console.log('=== pending_batches ===');
console.table(q(`SELECT kind, status, COUNT(*) n FROM pending_batches GROUP BY kind, status`));

console.log('=== sample pub_date format ===');
console.log(q(`SELECT pub_date FROM articles ORDER BY id DESC LIMIT 2`));

db.close();
