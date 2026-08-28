import { DatabaseSync } from 'node:sqlite';

// usage: node bench/db-audit/quality.mjs [db-path]   (default: logs/news.db)
const db = new DatabaseSync(process.argv[2] || 'logs/news.db', { readOnly: true });

const rows = db.prepare(`
  SELECT category, link, title, length(description) dlen, substr(description,1,400) dhead,
         (summary IS NOT NULL AND length(summary)>0) has_sum, status
  FROM articles WHERE pub_date >= datetime('now','-30 days')
`).all();

const hostOf = (url) => {
  try { return new URL(url).host.replace(/^www\./,''); } catch { return '<bad-url>'; }
};

const MARK = {
  reddit_boiler: d => /submitted by/.test(d) && /\[link\]/.test(d),
  hn_linkonly:   d => /Article URL:|Comments URL:/.test(d),
  html_junk:     d => /<table|<img|<a href|&#32;|<br\s*\/?>/.test(d),
  paywall:       d => /subscribe|sign in to|log in to|enable javascript|access denied|are you a robot|captcha/i.test(d),
  read_more:     d => /read more|continue reading|appeared first on|the post .* appeared/i.test(d),
  truncated1500: (d, len) => len === 1500,
};

const stats = new Map();
for (const r of rows) {
  const h = hostOf(r.link);
  let s = stats.get(h);
  if (!s) { s = { host: h, n: 0, empty: 0, tiny: 0, short: 0, sum: 0, reddit: 0, hn: 0, html: 0, pay: 0, more: 0, tr1500: 0, cats: new Set() }; stats.set(h, s); }
  s.n++; s.cats.add(r.category);
  const d = r.dhead || '';
  if (r.dlen === 0) s.empty++;
  else if (r.dlen <= 80) s.tiny++;
  else if (r.dlen <= 300) s.short++;
  if (r.has_sum) s.sum++;
  if (MARK.reddit_boiler(d)) s.reddit++;
  if (MARK.hn_linkonly(d)) s.hn++;
  if (MARK.html_junk(d)) s.html++;
  if (MARK.paywall(d)) s.pay++;
  if (MARK.read_more(d)) s.more++;
  if (MARK.truncated1500(d, r.dlen)) s.tr1500++;
}

const top = [...stats.values()].sort((a,b)=>b.n-a.n).slice(0, 30);
console.log('=== per-host quality, last 30d (top 30 by volume) ===');
console.table(top.map(s => ({
  host: s.host.slice(0,34), cats: [...s.cats].join(',').slice(0,18), n: s.n,
  'empty%': Math.round(100*s.empty/s.n), 'tiny%': Math.round(100*s.tiny/s.n), 'short%': Math.round(100*s.short/s.n),
  'sum%': Math.round(100*s.sum/s.n), reddit: s.reddit, hn: s.hn, html: s.html, pay: s.pay, more: s.more, tr1500: s.tr1500
})));

// worst offenders: hosts with >=20 articles and >=30% problematic (empty+tiny+short)
console.log('=== worst hosts (n>=20, weak-desc% >= 40) ===');
const worst = [...stats.values()].filter(s => s.n>=20 && (s.empty+s.tiny+s.short)/s.n >= 0.4)
  .sort((a,b)=> (b.empty+b.tiny+b.short)/b.n - (a.empty+a.tiny+a.short)/a.n);
console.table(worst.map(s => ({
  host: s.host.slice(0,40), n: s.n,
  'weak%': Math.round(100*(s.empty+s.tiny+s.short)/s.n),
  'sum%': Math.round(100*s.sum/s.n), reddit: s.reddit, html: s.html, more: s.more
})));

// concrete samples from a few problem classes
const sample = (label, sql) => {
  console.log(`\n=== samples: ${label} ===`);
  for (const r of db.prepare(sql).all()) {
    console.log(`- [${r.category}] ${r.title?.slice(0,90)}`);
    console.log(`  ${r.link?.slice(0,110)}`);
    console.log(`  desc(${r.dlen}): ${JSON.stringify((r.dhead||'').slice(0,180))}`);
  }
};
sample('empty description, no summary', `
  SELECT category, title, link, length(description) dlen, substr(description,1,200) dhead
  FROM articles WHERE pub_date >= datetime('now','-14 days') AND length(description)=0
  ORDER BY pub_date DESC LIMIT 8`);
sample('tiny description (<=80), no summary', `
  SELECT category, title, link, length(description) dlen, substr(description,1,200) dhead
  FROM articles WHERE pub_date >= datetime('now','-14 days') AND length(description) BETWEEN 1 AND 80
    AND (summary IS NULL OR length(summary)=0)
  ORDER BY pub_date DESC LIMIT 8`);
sample('reddit boilerplate', `
  SELECT category, title, link, length(description) dlen, substr(description,1,200) dhead
  FROM articles WHERE pub_date >= datetime('now','-14 days') AND description LIKE '%submitted by%' AND description LIKE '%[link]%'
  ORDER BY pub_date DESC LIMIT 6`);
sample('read-more stubs', `
  SELECT category, title, link, length(description) dlen, substr(description,1,200) dhead
  FROM articles WHERE pub_date >= datetime('now','-14 days')
    AND (description LIKE '%appeared first on%' OR description LIKE '%Continue reading%' OR description LIKE '%Read more%')
  ORDER BY pub_date DESC LIMIT 6`);

// which categories & hosts feed SUMMARIZE via openrouter (llm_calls has category)
console.log('\n=== llm_calls SUMMARIZE by category (14d) ===');
console.table(db.prepare(`SELECT provider, model, category, COUNT(*) n FROM llm_calls
  WHERE use_case='SUMMARIZE' AND ts >= datetime('now','-14 days') GROUP BY provider, model, category`).all());

// one full recent politics digest + one gaming digest as baseline reference
for (const cat of ['politics','gaming','tech']) {
  const r = db.prepare(`SELECT summary, created_at FROM summaries WHERE category=? ORDER BY created_at DESC LIMIT 1`).all(cat)[0];
  console.log(`\n=== latest ${cat} digest (${r?.created_at}) ===\n${r?.summary}`);
}

// sample of stored article summaries (prod nemotron output) with their inputs
console.log('\n=== sample stored summaries (tech, prod nemotron/gpt output) ===');
for (const r of db.prepare(`SELECT title, link, length(description) dlen, substr(summary,1,350) s
  FROM articles WHERE category='tech' AND summary IS NOT NULL AND length(summary)>0
    AND pub_date >= datetime('now','-7 days') ORDER BY pub_date DESC LIMIT 4`).all()) {
  console.log(`- ${r.title?.slice(0,80)} [dlen=${r.dlen}]`);
  console.log(`  summary: ${JSON.stringify(r.s)}\n`);
}
db.close();
