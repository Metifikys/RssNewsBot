import { DatabaseSync } from 'node:sqlite';

// usage: node bench/db-audit/quality2.mjs [db-path]   (default: logs/news.db)
const db = new DatabaseSync(process.argv[2] || 'logs/news.db', { readOnly: true });
const q = (sql, ...p) => db.prepare(sql).all(...p);
const hostOf = (u) => { try { return new URL(u).host.replace(/^www\./, ''); } catch { return '<bad>'; } };

// crude tag/entity strip to measure how much of the 1000-char prompt budget is markup
const strip = (s) => s
  .replace(/<[^>]+>/g, ' ')
  .replace(/&#?\w+;/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();

// 1) markup waste in promptText (first 1000 chars of description) per host, last 30d
const rows = q(`SELECT link, substr(description,1,1000) d FROM articles
  WHERE pub_date >= datetime('now','-30 days') AND length(description) > 100
    AND (description LIKE '%<p%' OR description LIKE '%<table%' OR description LIKE '%<div%'
         OR description LIKE '%<a href%' OR description LIKE '%<img%')`);
const byHost = new Map();
for (const r of rows) {
  const h = hostOf(r.link);
  const s = byHost.get(h) ?? { n: 0, raw: 0, clean: 0 };
  s.n++; s.raw += r.d.length; s.clean += strip(r.d).length;
  byHost.set(h, s);
}
console.log('=== markup share of prompt budget (hosts with html, n>=30, last 30d) ===');
console.table([...byHost.entries()].filter(([, s]) => s.n >= 30)
  .map(([h, s]) => ({ host: h.slice(0, 30), n: s.n, 'markup%': Math.round(100 * (1 - s.clean / s.raw)) }))
  .sort((a, b) => b['markup%'] - a['markup%']));

// 2) empty-description articles by host (reddit-rewrite fetch failures + youtube), last 30d
console.log('=== empty-desc by host (last 30d, n>=3) ===');
const emp = q(`SELECT link FROM articles WHERE pub_date >= datetime('now','-30 days') AND length(description)=0`);
const cnt = new Map();
for (const r of emp) cnt.set(hostOf(r.link), (cnt.get(hostOf(r.link)) ?? 0) + 1);
console.table([...cnt.entries()].filter(([, n]) => n >= 3).sort((a, b) => b[1] - a[1])
  .map(([h, n]) => ({ host: h, n })));
console.log('empty total (30d):', emp.length);

// 3) site-chrome boilerplate inside ENRICHED descriptions (fetchFullContent selector junk)
const chrome = [
  ['sud.ua', '%Слідкуйте за актуальними новинами%'],
  ['pravda.com.ua', '%Реклама:%'],
  ['pravda.com.ua', '%Підписуйся на%'],
  ['xda-developers.com', '%Sign in to your XDA account%'],
  ['wired.com', '%Save StorySave this story%'],
  ['tomshardware.com', '%Follow Tom%'],
  ['rbc.ua', '%Читайте нас у%'],
];
console.log('=== site-chrome boilerplate in enriched descriptions (last 30d) ===');
for (const [host, pat] of chrome) {
  const r = q(`SELECT COUNT(*) n FROM articles
    WHERE pub_date >= datetime('now','-30 days') AND link LIKE '%' || ? || '%' AND description LIKE ?`, host, pat)[0];
  const t = q(`SELECT COUNT(*) n FROM articles
    WHERE pub_date >= datetime('now','-30 days') AND link LIKE '%' || ? || '%'`, host)[0];
  console.log(`${host.padEnd(22)} ${String(pat).slice(0, 40).padEnd(42)} ${r.n}/${t.n}`);
}

// 4) teaser-only feeds (no fetchFullContent): median desc len per big host
console.log('=== teaser hosts: desc length percentiles (last 30d) ===');
for (const host of ['thegamer.com', 'pcgamer.com', 'gamesradar.com', 'techradar.com', 'techcrunch.com', 'itc.ua', 'gamedeveloper.com', 'siliconera.com', 'destructoid.com', 'kotaku.com']) {
  const ls = q(`SELECT length(description) l FROM articles
    WHERE pub_date >= datetime('now','-30 days') AND link LIKE '%' || ? || '%' ORDER BY l`, host).map(r => r.l);
  if (!ls.length) continue;
  const p = (x) => ls[Math.floor(ls.length * x)];
  console.log(`${host.padEnd(20)} n=${String(ls.length).padEnd(5)} p10=${p(0.1)} p50=${p(0.5)} p90=${p(0.9)}`);
}

// 5) how many articles reached a digest prompt with weak text (last 30d, PROCESSED only)
console.log('=== PROCESSED articles with weak prompt text, by category (30d) ===');
console.table(q(`SELECT category,
  COUNT(*) n,
  SUM(CASE WHEN length(COALESCE(NULLIF(summary,''), description)) < 200 THEN 1 ELSE 0 END) weak_lt200,
  SUM(CASE WHEN length(COALESCE(NULLIF(summary,''), description)) BETWEEN 200 AND 400 THEN 1 ELSE 0 END) mid_200_400
FROM articles WHERE pub_date >= datetime('now','-30 days') AND status='PROCESSED' GROUP BY category`));

// 6) donation/service posts from t.me feeds that reach prompts (mute candidates)
console.log('=== t.me service-noise candidates (30d) ===');
for (const pat of ['%monobank%', '%повітряна тривога%', '%відбій%', '%Донат%', '%збір%']) {
  const r = q(`SELECT COUNT(*) n FROM articles WHERE pub_date >= datetime('now','-30 days')
    AND link LIKE '%t.me%' AND description LIKE ?`, pat)[0];
  console.log(`${pat.padEnd(28)} ${r.n}`);
}
db.close();
