// Static weekly report over a news.db snapshot: ONE self-contained HTML page (inline CSS + SVG,
// no scripts, no CDN), opened straight from disk. Read-only; nothing is written to the DB.
//
//   node bench/db-audit/report.mjs [db=logs/news.db] [out=scratch/db-report.html]
//        [--days 30] [--stale 26] [--now "YYYY-MM-DD HH:MM"] [--prices prices.json] [--archive <dir>]
//
// Timestamps in the DB are the server's LOCAL time as text (`2026-09-23 07:18:56.421`), so every
// window is computed here as a local-time string (never SQLite's UTC `datetime('now')`). Run it on
// the server or on a machine in the same time zone, or pin the anchor with `--now`.
// `--archive <dir>` merges the retention archive (bench/lib/archive.mjs) for a longer history.
// `--prices` overrides/extends PRICES below: {"model-id or prefix*": {"input": $, "output": $}} per 1M.
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { mergeArchive, takeArchiveFlag } from '../lib/archive.mjs';

// ── args ──────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const archiveDir = takeArchiveFlag(argv);
const flag = (name, def) => {
  const i = argv.indexOf(name);
  if (i < 0) return def;
  const [, v] = argv.splice(i, 2);
  if (v === undefined) throw new Error(`${name} needs a value`);
  return v;
};
const DAYS = Number(flag('--days', 30));
const STALE_HOURS = Number(flag('--stale', 26));
const NOW_ARG = flag('--now', null);
const PRICES_FILE = flag('--prices', null);
const [DB_PATH = 'logs/news.db', OUT = 'scratch/db-report.html'] = argv;

// USD per 1M tokens, API list prices as of 2026-09 (approximate; `--prices` overrides). Matched
// by exact model id, then by the longest `prefix*`. `:free` OpenRouter models cost nothing.
// claudecli / codexcli run on a subscription: their figure is the API-equivalent, not a bill.
const PRICES = {
  'text-embedding-3-small': { input: 0.02, output: 0 },
  'text-embedding-3-large': { input: 0.13, output: 0 },
  'claude-sonnet-5*': { input: 2, output: 10 },
  'claude-sonnet-4*': { input: 3, output: 15 },
  'claude-opus-5*': { input: 5, output: 25 },
  'claude-haiku-4*': { input: 1, output: 5 },
  'gpt-5.4-mini*': { input: 0.25, output: 2 },
  'gpt-5-mini*': { input: 0.25, output: 2 },
};
const SUBSCRIPTION = new Set(['claudecli', 'codexcli']);
const RETENTION_NOTE = 'Retention trims history: rejected_events ~30 d, digest_messages / reaction_counts 90 d, ' +
  'covered_events 14 d (or feedback.lookbackDays), llm_calls llmCallRetentionDays. Pass --archive <dir> to include archived rows.';

// ── time: everything local ────────────────────────────────────────────────────
const pad = (n) => String(n).padStart(2, '0');
const fmtLocal = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
/** Parses the DB's local `YYYY-MM-DD HH:MM:SS[.fff]` text as a local Date. */
const parseLocal = (s) => {
  const m = /^(\d{4})-(\d\d)-(\d\d)[ T](\d\d):(\d\d)(?::(\d\d))?/.exec(s ?? '');
  return m ? new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] ?? 0)) : null;
};
const NOW = NOW_ARG ? parseLocal(NOW_ARG.length === 10 ? `${NOW_ARG} 23:59:59` : NOW_ARG) : new Date();
if (!NOW || Number.isNaN(DAYS) || DAYS <= 0) throw new Error('bad --now / --days');
const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const SINCE_DATE = new Date(startOfDay(NOW).getTime());
SINCE_DATE.setDate(SINCE_DATE.getDate() - (DAYS - 1));       // window = DAYS whole local days, today included
const SINCE = fmtLocal(SINCE_DATE);
const NOW_S = fmtLocal(NOW);
const hoursAgo = (h) => fmtLocal(new Date(NOW.getTime() - h * 3600_000));
const DAY_LIST = [];
for (let d = new Date(SINCE_DATE); d <= NOW; d.setDate(d.getDate() + 1)) DAY_LIST.push(fmtLocal(d).slice(0, 10));
const ageText = (s) => {
  const d = typeof s === 'number' ? new Date(s) : parseLocal(s);
  if (!d) return '';
  const h = (NOW - d) / 3600_000;
  if (h < 0) return 'in future';
  if (h < 1) return `${Math.round(h * 60)} min ago`;
  if (h < 48) return `${Math.round(h)} h ago`;
  return `${Math.round(h / 24)} d ago`;
};

// ── db ────────────────────────────────────────────────────────────────────────
const db = new DatabaseSync(DB_PATH, { readOnly: true });
const TABLES = new Set(db.prepare(`SELECT name FROM sqlite_master WHERE type='table'`).all().map((r) => r.name));
const has = (...t) => t.every((x) => TABLES.has(x));
let archiveFiles = 0;
if (archiveDir) {
  const wanted = ['articles', 'covered_events', 'rejected_events', 'llm_calls', 'summaries', 'digest_messages', 'reaction_counts'];
  archiveFiles = mergeArchive(db, archiveDir, wanted.filter((t) => TABLES.has(t)));
}
const q = (sql, ...p) => db.prepare(sql).all(...p).map((r) => ({ ...r }));
const one = (sql, ...p) => q(sql, ...p)[0] ?? {};

// ── html helpers ──────────────────────────────────────────────────────────────
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const num = (n, digits = 0) => (digits = typeof digits === 'number' ? digits : 0, // also used as fmt(value, row)
  n == null || Number.isNaN(n) ? '—' : Number(n).toLocaleString('en-US', { maximumFractionDigits: digits, minimumFractionDigits: digits }));
const pct = (a, b) => (b ? `${(100 * a / b).toFixed(1)}%` : '—');
const usd = (n) => (n == null ? '—' : n < 0.01 && n > 0 ? '<$0.01' : `$${n.toFixed(2)}`);
const tok = (n) => (n >= 1e6 ? `${(n / 1e6).toFixed(2)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}k` : String(n ?? 0));
const EMPTY = '<p class="empty">No data in this window.</p>';
const absent = (t) => `<p class="empty">Table <code>${esc(t)}</code> is not in this DB (older schema).</p>`;

/** cols: [{ key, label, fmt?, num?: true, cls?: (row) => string }] */
function table(rows, cols, { empty = EMPTY, cap } = {}) {
  if (!rows.length) return empty;
  const shown = cap ? rows.slice(0, cap) : rows;
  const head = cols.map((c) => `<th${c.num ? ' class="n"' : ''}>${esc(c.label)}</th>`).join('');
  const body = shown.map((r) => `<tr>${cols.map((c) => {
    const v = c.fmt ? c.fmt(r[c.key], r) : esc(r[c.key]);
    const cls = [c.num ? 'n' : '', c.cls ? c.cls(r) : ''].filter(Boolean).join(' ');
    return `<td${cls ? ` class="${cls}"` : ''}>${v}</td>`;
  }).join('')}</tr>`).join('\n');
  const more = cap && rows.length > cap ? `<p class="note">${rows.length - cap} more rows not shown.</p>` : '';
  return `<div class="tw"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>${more}`;
}

// Categorical slots in fixed order (validated default palette); colour follows the entity name,
// assigned in sorted order so the same category keeps its colour across every chart.
const SLOTS = 8;
const colorIndex = new Map();
const slotOf = (name) => {
  if (!colorIndex.has(name)) colorIndex.set(name, Math.min(colorIndex.size, SLOTS - 1));
  return colorIndex.get(name) + 1;
};

/** Stacked daily bars. series: [{ name, values: Map(day -> n) }]; one tooltip per segment. */
function stackedDays(series, { unit = '', fmtV = num } = {}) {
  if (!series.length || series.every((s) => [...s.values.values()].every((v) => !v))) return EMPTY;
  const W = 900, H = 220, L = 44, B = 22, T = 8;
  const totals = DAY_LIST.map((d) => series.reduce((a, s) => a + (s.values.get(d) ?? 0), 0));
  const max = Math.max(...totals, 1);
  const step = niceStep(max / 4);
  const top = Math.ceil(max / step) * step;
  const bw = (W - L) / DAY_LIST.length;
  const y = (v) => T + (H - T - B) * (1 - v / top);
  let svg = '';
  for (let v = 0; v <= top; v += step) {
    svg += `<line class="grid" x1="${L}" x2="${W}" y1="${y(v)}" y2="${y(v)}"/><text class="tick" x="${L - 6}" y="${y(v) + 4}" text-anchor="end">${esc(tok(v))}</text>`;
  }
  DAY_LIST.forEach((d, i) => {
    const x = L + i * bw + 1;
    let acc = 0;
    for (const s of series) {
      const v = s.values.get(d) ?? 0;
      if (!v) continue;
      const y0 = y(acc), y1 = y(acc + v);
      acc += v;
      svg += `<rect class="s${slotOf(s.name)}" x="${x.toFixed(1)}" y="${y1.toFixed(1)}" width="${Math.max(bw - 2, 1).toFixed(1)}" height="${Math.max(y0 - y1, 0.5).toFixed(1)}"><title>${esc(d)} · ${esc(s.name)}: ${fmtV(v)}${unit} (day total ${fmtV(totals[i])}${unit})</title></rect>`;
    }
    if (i % Math.ceil(DAY_LIST.length / 10) === 0 || i === DAY_LIST.length - 1) {
      svg += `<text class="tick" x="${(x + bw / 2).toFixed(1)}" y="${H - 6}" text-anchor="middle">${d.slice(5)}</text>`;
    }
  });
  svg += `<line class="axis" x1="${L}" x2="${W}" y1="${y(0)}" y2="${y(0)}"/>`;
  const legend = series.length > 1
    ? `<div class="legend">${series.map((s) => `<span><i class="s${slotOf(s.name)}"></i>${esc(s.name)}</span>`).join('')}</div>` : '';
  return `${legend}<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" preserveAspectRatio="none">${svg}</svg>`;
}
function niceStep(raw) {
  const p = 10 ** Math.floor(Math.log10(Math.max(raw, 1e-9)));
  return [1, 2, 5, 10].map((m) => m * p).find((s) => s >= raw) ?? 10 * p;
}
/** Rows [{ key, day, v }] -> series for stackedDays, largest series first. */
function toSeries(rows, key = 'key', v = 'v') {
  const by = new Map();
  for (const r of rows) {
    const name = r[key] ?? '(none)';
    if (!by.has(name)) by.set(name, new Map());
    by.get(name).set(r.day, (by.get(name).get(r.day) ?? 0) + (r[v] ?? 0));
  }
  return [...by.entries()].sort(([a], [b]) => String(a).localeCompare(String(b)))
    .map(([name, values]) => ({ name, values, total: [...values.values()].reduce((a, b) => a + b, 0) }));
}
const tiles = (items) => `<div class="tiles">${items.map(([label, value, sub]) =>
  `<div class="tile"><div class="tl">${esc(label)}</div><div class="tv">${value}</div>${sub ? `<div class="ts">${sub}</div>` : ''}</div>`).join('')}</div>`;
const section = (id, title, body, intro = '') =>
  `<section id="${id}"><h2>${esc(title)}</h2>${intro ? `<p class="intro">${intro}</p>` : ''}${body}</section>`;

// ── pricing ───────────────────────────────────────────────────────────────────
const prices = { ...PRICES, ...(PRICES_FILE ? JSON.parse(readFileSync(PRICES_FILE, 'utf8')) : {}) };
function priceOf(model) {
  if (/:free$/.test(model)) return { input: 0, output: 0 };
  if (prices[model]) return prices[model];
  const hit = Object.keys(prices).filter((k) => k.endsWith('*') && model.startsWith(k.slice(0, -1)))
    .sort((a, b) => b.length - a.length)[0];
  return hit ? prices[hit] : null;
}
/** DB cost when the bot had `pricing:` set, else our estimate; null = unknown model. */
function costOf(r) {
  if (r.db_cost > 0) return r.db_cost;
  const p = priceOf(r.model);
  if (!p) return null;
  const raw = (r.p_tok / 1e6) * p.input + (r.c_tok / 1e6) * p.output;
  return r.use_case === 'BATCH' ? raw / 2 : raw;
}

// ══ sections ══════════════════════════════════════════════════════════════════
const sections = [];
const summary = {};

// 1. Posts ─────────────────────────────────────────────────────────────────────
{
  let body = '';
  if (!has('digest_messages')) body = absent('digest_messages');
  else {
    const daily = q(`SELECT category key, date(sent_at) day, COUNT(*) v FROM digest_messages
                     WHERE sent_at >= ? AND sent_at <= ? GROUP BY 1,2`, SINCE, NOW_S);
    const perCat = q(`SELECT category, COUNT(*) msgs, COUNT(DISTINCT event_key) events, MAX(sent_at) last
                      FROM digest_messages WHERE sent_at >= ? AND sent_at <= ? GROUP BY 1 ORDER BY 1`, SINCE, NOW_S);
    const digests = has('summaries') ? new Map(q(`SELECT category, COUNT(*) n, SUM(article_count) topics
                      FROM summaries WHERE created_at >= ? AND created_at <= ? GROUP BY 1`, SINCE, NOW_S).map((r) => [r.category, r])) : new Map();
    for (const r of perCat) {
      r.digests = digests.get(r.category)?.n ?? 0;
      r.topics = digests.get(r.category)?.topics ?? 0;
      r.perDay = r.msgs / DAY_LIST.length;
    }
    summary.msgs = perCat.reduce((a, r) => a + r.msgs, 0);
    summary.msgs7 = one(`SELECT COUNT(*) n FROM digest_messages WHERE sent_at >= ? AND sent_at <= ?`, hoursAgo(24 * 7), NOW_S).n;
    body = (daily.length ? stackedDays(toSeries(daily), { unit: ' messages' }) : '') + table(perCat, [
      { key: 'category', label: 'category' },
      { key: 'msgs', label: 'messages', num: true, fmt: num },
      { key: 'perDay', label: 'per day', num: true, fmt: (v) => num(v, 1) },
      { key: 'events', label: 'distinct events', num: true, fmt: num },
      { key: 'digests', label: 'digest runs', num: true, fmt: num },
      { key: 'topics', label: 'topics (Σ article_count)', num: true, fmt: num },
      { key: 'last', label: 'last message', fmt: (v) => `${esc(v?.slice(0, 16))} <span class="muted">${ageText(v)}</span>` },
    ]);
  }
  sections.push(section('posts', 'Posts per category and day', body,
    'Channel messages from <code>digest_messages</code> (a threaded post counts once per message); digest runs and topics from <code>summaries</code>. Legacy-flow categories carry no event key.'));
}

// 2. Reactions ─────────────────────────────────────────────────────────────────
{
  let body = '';
  if (!has('digest_messages', 'reaction_counts')) body = absent('reaction_counts');
  else {
    const rows = q(`SELECT dm.category, rc.emoji, SUM(rc.count) n FROM reaction_counts rc
                    JOIN digest_messages dm ON dm.chat_id = rc.chat_id AND dm.message_id = rc.message_id
                    WHERE dm.sent_at >= ? AND dm.sent_at <= ? GROUP BY 1,2`, SINCE, NOW_S);
    const posts = q(`SELECT dm.category, COUNT(*) msgs,
                       SUM(EXISTS(SELECT 1 FROM reaction_counts rc WHERE rc.chat_id = dm.chat_id AND rc.message_id = dm.message_id AND rc.count > 0)) reacted
                     FROM digest_messages dm WHERE dm.sent_at >= ? AND dm.sent_at <= ? GROUP BY 1 ORDER BY 1`, SINCE, NOW_S);
    const emojis = [...new Set(rows.map((r) => r.emoji))]
      .sort((a, b) => rows.filter((r) => r.emoji === b).reduce((s, r) => s + r.n, 0) - rows.filter((r) => r.emoji === a).reduce((s, r) => s + r.n, 0))
      .slice(0, 8);
    for (const p of posts) {
      p.total = rows.filter((r) => r.category === p.category).reduce((s, r) => s + r.n, 0);
      for (const e of emojis) p[`e:${e}`] = rows.find((r) => r.category === p.category && r.emoji === e)?.n ?? 0;
    }
    summary.reactions = posts.reduce((a, p) => a + p.total, 0);
    summary.reactions7 = one(`SELECT SUM(rc.count) n FROM reaction_counts rc JOIN digest_messages dm
                              ON dm.chat_id = rc.chat_id AND dm.message_id = rc.message_id WHERE dm.sent_at >= ? AND dm.sent_at <= ?`,
                              hoursAgo(24 * 7), NOW_S).n ?? 0;
    body += table(posts, [
      { key: 'category', label: 'category' },
      { key: 'msgs', label: 'messages', num: true, fmt: num },
      { key: 'reacted', label: 'with a reaction', num: true, fmt: (v, r) => `${num(v)} <span class="muted">${pct(v, r.msgs)}</span>` },
      { key: 'total', label: 'reactions', num: true, fmt: num },
      ...emojis.map((e) => ({ key: `e:${e}`, label: e, num: true, fmt: num })),
    ]);
    const hasCovered = has('covered_events');
    const top = q(`SELECT dm.category, dm.sent_at, dm.event_key, SUM(rc.count) total,
                     GROUP_CONCAT(rc.emoji || rc.count, ' ') detail
                     ${hasCovered ? `, (SELECT ce.subject || CASE WHEN ce.franchise <> '' THEN ' · ' || ce.franchise ELSE '' END
                          FROM covered_events ce WHERE ce.category = dm.category AND ce.event_key = dm.event_key LIMIT 1) subject` : ', NULL subject'}
                   FROM reaction_counts rc JOIN digest_messages dm ON dm.chat_id = rc.chat_id AND dm.message_id = rc.message_id
                   WHERE dm.sent_at >= ? AND dm.sent_at <= ? GROUP BY dm.chat_id, dm.message_id ORDER BY total DESC LIMIT 10`, SINCE, NOW_S);
    body += `<h3>Top 10 messages by reactions</h3>` + table(top, [
      { key: 'category', label: 'category' },
      { key: 'sent_at', label: 'sent', fmt: (v) => esc(v?.slice(0, 16)) },
      { key: 'subject', label: 'event', fmt: (v, r) => esc(v || r.event_key || '(no event key)').slice(0, 160) },
      { key: 'total', label: 'Σ', num: true, fmt: num },
      { key: 'detail', label: 'emoji' },
    ]);
  }
  sections.push(section('reactions', 'Reactions', body,
    'Current reaction counts of messages sent in the window (<code>reaction_counts</code> ⋈ <code>digest_messages</code>).'));
}

// 3. LLM calls ─────────────────────────────────────────────────────────────────
{
  let body = '';
  if (!has('llm_calls')) body = absent('llm_calls');
  else {
    const rows = q(`SELECT provider, model, use_case, COUNT(*) calls, SUM(prompt_tokens) p_tok, SUM(completion_tokens) c_tok,
                      SUM(est_cost_usd) db_cost, AVG(duration_ms) avg_ms, MAX(duration_ms) max_ms
                    FROM llm_calls WHERE ts >= ? AND ts <= ? GROUP BY 1,2,3 ORDER BY calls DESC`, SINCE, NOW_S);
    // p95 latency per group, computed here (SQLite has no percentile).
    const durs = q(`SELECT provider, model, use_case, duration_ms d FROM llm_calls
                    WHERE ts >= ? AND ts <= ? AND duration_ms IS NOT NULL`, SINCE, NOW_S);
    const byKey = new Map();
    for (const r of durs) {
      const k = `${r.provider}|${r.model}|${r.use_case}`;
      if (!byKey.has(k)) byKey.set(k, []);
      byKey.get(k).push(r.d);
    }
    const unknown = new Set();
    let apiCost = 0, subCost = 0;
    for (const r of rows) {
      const ds = (byKey.get(`${r.provider}|${r.model}|${r.use_case}`) ?? []).sort((a, b) => a - b);
      r.p95 = ds.length ? ds[Math.min(ds.length - 1, Math.floor(ds.length * 0.95))] : null;
      r.cost = costOf(r);
      r.sub = SUBSCRIPTION.has(r.provider);
      if (r.cost == null) unknown.add(`${r.provider}/${r.model}`);
      else if (r.sub) subCost += r.cost;
      else apiCost += r.cost;
    }
    summary.llmCalls = rows.reduce((a, r) => a + r.calls, 0);
    summary.apiCost = apiCost;
    summary.subCost = subCost;
    const daily = q(`SELECT provider key, date(ts) day, SUM(prompt_tokens + completion_tokens) v FROM llm_calls
                     WHERE ts >= ? AND ts <= ? GROUP BY 1,2`, SINCE, NOW_S);
    body += `<h3>Tokens per day by provider</h3>` + stackedDays(toSeries(daily), { unit: ' tokens', fmtV: tok });
    body += `<h3>By provider / model / use case</h3>` + table(rows, [
      { key: 'provider', label: 'provider', fmt: (v, r) => `${esc(v)}${r.sub ? ' <span class="badge">subscription</span>' : ''}` },
      { key: 'model', label: 'model' },
      { key: 'use_case', label: 'use case' },
      { key: 'calls', label: 'calls', num: true, fmt: num },
      { key: 'p_tok', label: 'prompt tok', num: true, fmt: tok },
      { key: 'c_tok', label: 'completion tok', num: true, fmt: tok },
      { key: 'avg_ms', label: 'avg s', num: true, fmt: (v) => (v == null ? '—' : num(v / 1000, 1)) },
      { key: 'p95', label: 'p95 s', num: true, fmt: (v) => (v == null ? '—' : num(v / 1000, 1)) },
      { key: 'cost', label: 'cost', num: true, fmt: (v, r) => (v == null ? '<span class="muted">no price</span>' : `${usd(v)}${r.sub ? '<span class="muted">*</span>' : ''}`) },
    ]);
    const perCat = q(`SELECT COALESCE(category, '(none)') category, use_case, COUNT(*) calls, SUM(prompt_tokens) p_tok, SUM(completion_tokens) c_tok
                      FROM llm_calls WHERE ts >= ? AND ts <= ? GROUP BY 1,2 ORDER BY 1,2`, SINCE, NOW_S);
    body += `<h3>By category / use case</h3>` + table(perCat, [
      { key: 'category', label: 'category' }, { key: 'use_case', label: 'use case' },
      { key: 'calls', label: 'calls', num: true, fmt: num },
      { key: 'p_tok', label: 'prompt tok', num: true, fmt: tok }, { key: 'c_tok', label: 'completion tok', num: true, fmt: tok },
    ]);
    body += `<p class="note">Cost = <code>est_cost_usd</code> when the bot has <code>pricing:</code> configured, otherwise tokens × the price table in the script (override with <code>--prices</code>); BATCH at 50 %. ` +
      `* subscription CLIs: API-equivalent, not billed.${unknown.size ? ` No price for: ${[...unknown].map(esc).join(', ')}.` : ''}</p>`;
  }
  sections.push(section('llm', 'LLM calls', body));
}

// 4. Feed / domain health ──────────────────────────────────────────────────────
const SILENT_DAYS = 3;
const REGULAR_MIN = 10;   // a source with fewer articles in the window is a one-off link target, not a feed
/** Feed-ish source key: host, plus the channel / subreddit for the shared hosts. */
function sourceOf(link) {
  try {
    const u = new URL(link);
    const host = u.host.replace(/^www\.|^old\./, '');
    const seg = u.pathname.split('/').filter(Boolean);
    if (host === 't.me' && seg[0]) return `t.me/${seg[0] === 's' ? seg[1] : seg[0]}`;
    if (host.endsWith('reddit.com') && seg[0] === 'r' && seg[1]) return `reddit.com/r/${seg[1]}`;
    return host;
  } catch { return '<bad url>'; }
}
{
  let body = '';
  if (!has('articles')) body = absent('articles');
  else {
    const rows = q(`SELECT category, link, pub_date, status FROM articles WHERE pub_date >= ?`, SINCE);
    const src = new Map();
    for (const r of rows) {
      const k = `${r.category}\t${sourceOf(r.link)}`;
      let s = src.get(k);
      if (!s) src.set(k, (s = { category: r.category, source: sourceOf(r.link), n: 0, dup: 0, processed: 0, last: '' }));
      s.n++;
      if (r.status === 'DUPLICATE') s.dup++;
      if (r.status === 'PROCESSED') s.processed++;
      if (r.pub_date <= NOW_S && r.pub_date > s.last) s.last = r.pub_date;
    }
    const all = [...src.values()];
    const silentBefore = hoursAgo(24 * SILENT_DAYS);
    for (const s of all) {
      s.perDay = s.n / DAY_LIST.length;
      s.silent = s.n >= REGULAR_MIN && (!s.last || s.last < silentBefore);
    }
    const silent = all.filter((s) => s.silent).sort((a, b) => (a.last < b.last ? -1 : 1));
    const byVol = all.sort((a, b) => b.n - a.n);
    const future = rows.filter((r) => r.pub_date > NOW_S).length;
    summary.sources = all.length;
    summary.silent = silent.length;
    const cols = [
      { key: 'source', label: 'source' }, { key: 'category', label: 'category' },
      { key: 'n', label: 'articles', num: true, fmt: num },
      { key: 'perDay', label: 'per day', num: true, fmt: (v) => num(v, 1) },
      { key: 'dup', label: 'semantic dup', num: true, fmt: (v, r) => `${num(v)} <span class="muted">${pct(v, r.n)}</span>` },
      { key: 'last', label: 'newest pub_date', fmt: (v) => `${esc(v?.slice(0, 16) || '—')} <span class="muted">${ageText(v)}</span>`, cls: (r) => (r.silent ? 'bad' : '') },
    ];
    body += `<h3>Regular sources silent for more than ${SILENT_DAYS} days (${silent.length})</h3>` +
      table(silent, cols, { empty: `<p class="empty">Every source with ${REGULAR_MIN}+ articles in the window published within the last ${SILENT_DAYS} days.</p>` });
    body += `<h3>Top sources by volume</h3>` + table(byVol, cols, { cap: 40 });
    body += `<p class="note">Sources are derived from article links (there is no feed table): host, or t.me channel / subreddit; "regular" = ${REGULAR_MIN}+ articles in the window. ` +
      `Windowed by the feed's own <code>pub_date</code>; a feed dead since before the window does not appear. ${future} article(s) carry a future pub_date and are ignored for "newest".</p>`;
  }
  sections.push(section('feeds', 'Feed / domain health', body));
}

// 5. Dedup layers ──────────────────────────────────────────────────────────────
{
  let body = '';
  if (!has('articles')) body = absent('articles');
  else {
    const cats = new Map();
    const get = (c) => cats.get(c) ?? cats.set(c, { category: c, ingested: 0, l2: 0, rejected: 0, dupStep1: 0, covered: 0 }).get(c);
    for (const r of q(`SELECT category, COUNT(*) n, SUM(status = 'DUPLICATE') dup FROM articles WHERE pub_date >= ? AND pub_date <= ? GROUP BY 1`, SINCE, NOW_S)) {
      Object.assign(get(r.category), { ingested: r.n, l2: r.dup });
    }
    if (has('rejected_events')) {
      for (const r of q(`SELECT category, status, COUNT(*) n FROM rejected_events WHERE extracted_at >= ? AND extracted_at <= ? GROUP BY 1,2`, SINCE, NOW_S)) {
        if (r.status === 'duplicate') get(r.category).dupStep1 += r.n; else get(r.category).rejected += r.n;
      }
    }
    if (has('covered_events')) {
      for (const r of q(`SELECT category, COUNT(*) n FROM covered_events WHERE covered_at >= ? AND covered_at <= ? GROUP BY 1`, SINCE, NOW_S)) get(r.category).covered = r.n;
    }
    const rows = [...cats.values()].sort((a, b) => a.category.localeCompare(b.category));
    const oldest = (t, c) => (has(t) ? one(`SELECT MIN(${c}) m FROM ${t}`).m : null);
    body += table(rows, [
      { key: 'category', label: 'category' },
      { key: 'ingested', label: 'ingested (by pub_date)', num: true, fmt: num },
      { key: 'l2', label: 'L2 semantic dup', num: true, fmt: (v, r) => `${num(v)} <span class="muted">${pct(v, r.ingested)}</span>` },
      { key: 'rejected', label: 'Step 1 rejected', num: true, fmt: num },
      { key: 'dupStep1', label: 'Step 1 duplicate (L3)', num: true, fmt: num },
      { key: 'covered', label: 'Step 1 covered', num: true, fmt: num },
      { key: 'covered', label: 'covered share of Step 1', num: true, fmt: (v, r) => pct(v, v + r.rejected + r.dupStep1) },
    ]);
    body += `<p class="note">Not persisted, so not counted here: L1 (unique link at insert), Step 0 keyword mute, cooldown / <code>[Ranker] drop</code>, L3.5. ` +
      `Oldest rows kept: rejected_events ${esc(oldest('rejected_events', 'extracted_at')?.slice(0, 10) ?? '—')}, covered_events ${esc(oldest('covered_events', 'covered_at')?.slice(0, 10) ?? '—')} — ` +
      `earlier days in the window read as zero.</p>`;
  }
  sections.push(section('dedup', 'Dedup layers', body));
}

// 6. Queue, orphans, batches ───────────────────────────────────────────────────
{
  let body = '';
  if (!has('articles')) body = absent('articles');
  else {
    const st = q(`SELECT category, status, COUNT(*) n FROM articles GROUP BY 1,2`);
    const statuses = [...new Set(st.map((r) => r.status))].sort();
    const pivot = [...new Set(st.map((r) => r.category))].sort().map((c) => {
      const row = { category: c };
      for (const s of statuses) row[s] = st.find((r) => r.category === c && r.status === s)?.n ?? 0;
      return row;
    });
    body += `<h3>Article status, all rows</h3>` + table(pivot, [{ key: 'category', label: 'category' },
      ...statuses.map((s) => ({ key: s, label: s, num: true, fmt: num }))], { empty: '<p class="empty">No articles.</p>' });
    const staleBefore = hoursAgo(STALE_HOURS);
    const queue = q(`SELECT category, status, COUNT(*) n, MIN(pub_date) oldest_pub, MIN(processing_started_at) oldest_start,
                       SUM(status = 'PROCESSING' AND (processing_started_at IS NULL OR processing_started_at < ?)) orphans
                     FROM articles WHERE status IN ('UNPROCESSED', 'PROCESSING') GROUP BY 1,2 ORDER BY 1,2`, staleBefore);
    summary.pending = queue.filter((r) => r.status === 'UNPROCESSED').reduce((a, r) => a + r.n, 0);
    summary.processing = queue.filter((r) => r.status === 'PROCESSING').reduce((a, r) => a + r.n, 0);
    summary.orphans = queue.reduce((a, r) => a + (r.orphans ?? 0), 0);
    body += `<h3>Pending and in-flight</h3>` + table(queue, [
      { key: 'category', label: 'category' }, { key: 'status', label: 'status' },
      { key: 'n', label: 'articles', num: true, fmt: num },
      { key: 'oldest_pub', label: 'oldest pub_date', fmt: (v) => `${esc(v?.slice(0, 16))} <span class="muted">${ageText(v)}</span>` },
      { key: 'oldest_start', label: 'oldest processing start', fmt: (v) => (v ? `${esc(v.slice(0, 16))} <span class="muted">${ageText(v)}</span>` : '—') },
      { key: 'orphans', label: `orphans (> ${STALE_HOURS} h)`, num: true, fmt: num, cls: (r) => (r.orphans ? 'bad' : '') },
    ], { empty: '<p class="empty">Queue is empty: nothing UNPROCESSED or PROCESSING.</p>' });
    if (has('pending_batches')) {
      const b = q(`SELECT kind, status, COUNT(*) n, MIN(created_at) oldest, MAX(created_at) newest FROM pending_batches GROUP BY 1,2 ORDER BY 1,2`);
      body += `<h3>Batch API jobs (<code>pending_batches</code>)</h3>` + table(b, [
        { key: 'kind', label: 'kind' }, { key: 'status', label: 'status', cls: (r) => (r.status === 'pending' && ageText(r.oldest).endsWith('d ago') ? 'bad' : '') },
        { key: 'n', label: 'jobs', num: true, fmt: num },
        { key: 'oldest', label: 'oldest', fmt: (v) => `${esc(v?.slice(0, 16))} <span class="muted">${ageText(v)}</span>` },
        { key: 'newest', label: 'newest', fmt: (v) => `${esc(v?.slice(0, 16))} <span class="muted">${ageText(v)}</span>` },
      ]);
    }
  }
  sections.push(section('queue', 'Article queue and orphans', body,
    `Orphan = PROCESSING whose <code>processing_started_at</code> is older than <code>--stale ${STALE_HOURS}</code> h (the bot reclaims these at startup).`));
}

// 7. Admin actions, pauses, CLI health ─────────────────────────────────────────
{
  let body = '';
  const state = has('bot_state') ? q(`SELECT key, value FROM bot_state ORDER BY key`) : [];
  // CLI health: `cli_health:<provider>` = STATE|sinceMs|alerted|lastOkMs|lastProbeMs (CliHealthMonitor).
  const cli = state.filter((r) => /^cli_health:(claude|codex)$/.test(r.key)).map((r) => {
    const [st, since, alerted, lastOk, lastProbe] = r.value.split('|');
    const ms = (v) => (v ? Number(v) : null);
    return { provider: r.key.split(':')[1], state: st, since: ms(since), alerted, lastOk: ms(lastOk), lastProbe: ms(lastProbe) };
  });
  const tokenWarned = state.find((r) => r.key === 'cli_health:claude_token_warned')?.value;
  summary.cli = !cli.length ? '—' : cli.filter((c) => c.state !== 'OK').map((c) => `${c.provider} ${c.state}`).join(', ') || 'OK';
  const epoch = (v) => (v == null ? '—' : `${esc(fmtLocal(new Date(v)).slice(0, 16))} <span class="muted">${ageText(v)}</span>`);
  body += `<h3>CLI health</h3>` + table(cli, [
    { key: 'provider', label: 'CLI' },
    { key: 'state', label: 'state', cls: (r) => (r.state === 'OK' ? 'good' : 'bad') },
    { key: 'since', label: 'since', fmt: epoch },
    { key: 'alerted', label: 'last alerted state', fmt: (v) => esc(v || '—') },
    { key: 'lastOk', label: 'last OK run', fmt: epoch },
    { key: 'lastProbe', label: 'last probe', fmt: epoch },
  ], { empty: '<p class="empty">No CLI health state recorded (<code>health.enabled</code> off, or no CLI configured).</p>' }) +
    (tokenWarned ? `<p class="note">Claude setup-token expiry last warned on ${esc(tokenWarned)}.</p>` : '');

  const pauses = state.filter((r) => r.key.startsWith('pause:')).map((r) => ({ category: r.key.slice(6), until: r.value }));
  const other = state.filter((r) => /^(affinity_last_recompute|mute_suggest:last)$/.test(r.key))
    .map((r) => ({ key: r.key, value: r.value.split('|')[0].replace('T', ' ').slice(0, 16) }));
  body += `<h3>Digest pauses</h3>` + table(pauses, [{ key: 'category', label: 'category' },
    { key: 'until', label: 'paused until', fmt: (v) => esc(v === 'INDEFINITE' ? 'until /resume' : v.replace('T', ' ').slice(0, 16)) }],
  { empty: '<p class="empty">No category is paused.</p>' });
  if (other.length) body += `<h3>Maintenance jobs</h3>` + table(other, [{ key: 'key', label: 'job' }, { key: 'value', label: 'last run' }]);

  body += `<h3>Keyword mutes from Telegram (<code>admin_mutes</code>)</h3>`;
  body += has('admin_mutes') ? table(q(`SELECT keyword, COALESCE(category, 'all') category, source, created_at FROM admin_mutes ORDER BY created_at DESC`), [
    { key: 'keyword', label: 'keyword' }, { key: 'category', label: 'category' }, { key: 'source', label: 'source' },
    { key: 'created_at', label: 'added', fmt: (v) => `${esc(v?.slice(0, 16))} <span class="muted">${ageText(v)}</span>` },
  ], { empty: '<p class="empty">No DB mutes (only the YAML lists apply).</p>', cap: 50 }) : absent('admin_mutes');

  body += `<h3>Mute proposals (<code>mute_suggestions</code>)</h3>`;
  if (has('mute_suggestions')) {
    const counts = q(`SELECT status, COUNT(*) n FROM mute_suggestions GROUP BY 1`).map((r) => `${esc(r.status)} ${r.n}`).join(' · ');
    body += (counts ? `<p class="note">All time: ${counts}</p>` : '') + table(q(`SELECT category, keyword, source kind, status, hits30d, published30d, created_at, decided_at
        FROM mute_suggestions WHERE created_at >= ? ORDER BY created_at DESC`, SINCE), [
      { key: 'category', label: 'category' }, { key: 'keyword', label: 'keyword' }, { key: 'kind', label: 'kind' },
      { key: 'status', label: 'status', cls: (r) => (r.status === 'pending' ? 'warn' : '') },
      { key: 'hits30d', label: 'hits 30 d', num: true, fmt: num }, { key: 'published30d', label: 'published 30 d', num: true, fmt: num },
      { key: 'created_at', label: 'proposed', fmt: (v) => esc(v?.slice(0, 16)) },
      { key: 'decided_at', label: 'decided', fmt: (v) => esc(v?.slice(0, 16) ?? '—') },
    ], { cap: 40 });
  } else body += absent('mute_suggestions');

  body += `<h3>Moderation buttons (<code>admin_feedback</code>)</h3>`;
  if (has('admin_feedback')) {
    const fb = q(`SELECT category, SUM(signal = 1) more, SUM(signal = -1) less, SUM(signal = -2) deleted, MAX(created_at) last
                  FROM admin_feedback WHERE created_at >= ? AND created_at <= ? GROUP BY 1 ORDER BY 1`, SINCE, NOW_S);
    body += table(fb, [
      { key: 'category', label: 'category' }, { key: 'more', label: 'more', num: true, fmt: num },
      { key: 'less', label: 'less', num: true, fmt: num }, { key: 'deleted', label: 'deleted', num: true, fmt: num },
      { key: 'last', label: 'last press', fmt: (v) => `${esc(v?.slice(0, 16))} <span class="muted">${ageText(v)}</span>` },
    ], { empty: '<p class="empty">No moderation presses in the window.</p>' });
  } else body += absent('admin_feedback');
  sections.push(section('admin', 'Admin actions, pauses and CLI health', body));
}

// 8. Audience affinity snapshot ────────────────────────────────────────────────
{
  let body = '';
  if (!has('audience_affinity')) body = absent('audience_affinity');
  else {
    const rows = q(`SELECT category, dimension, key, n, score FROM audience_affinity ORDER BY category, dimension, score DESC`);
    const groups = new Map();
    for (const r of rows) {
      const k = `${r.category}\t${r.dimension}`;
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(r);
    }
    const out = [];
    for (const list of groups.values()) {
      const pick = list.length <= 10 ? list : [...list.slice(0, 5), ...list.slice(-5)];
      out.push({ category: list[0].category, dimension: list[0].dimension, count: list.length,
        top: pick.filter((r) => r.score > 0).map((r) => `${r.key} (${r.score.toFixed(2)}, n ${num(r.n, 1)})`).join(', '),
        bottom: pick.filter((r) => r.score < 0).reverse().map((r) => `${r.key} (${r.score.toFixed(2)}, n ${num(r.n, 1)})`).join(', ') });
    }
    body = table(out, [
      { key: 'category', label: 'category' }, { key: 'dimension', label: 'dimension' },
      { key: 'count', label: 'keys', num: true, fmt: num },
      { key: 'top', label: 'liked (top 5)', fmt: (v) => esc(v || '—') },
      { key: 'bottom', label: 'disliked (bottom 5)', fmt: (v) => esc(v || '—') },
    ], { empty: '<p class="empty">Affinity table is empty (<code>feedback.enabled</code> off or not yet computed).</p>' });
  }
  sections.push(section('affinity', 'Audience affinity snapshot', body));
}

// ── page ──────────────────────────────────────────────────────────────────────
const latest = [
  has('digest_messages') && `last message ${one(`SELECT MAX(sent_at) m FROM digest_messages`).m?.slice(0, 16) ?? '—'}`,
  has('llm_calls') && `last LLM call ${one(`SELECT MAX(ts) m FROM llm_calls`).m?.slice(0, 16) ?? '—'}`,
].filter(Boolean).join(' · ');
const head = tiles([
  ['Messages', num(summary.msgs), `${num(summary.msgs7)} in the last 7 d`],
  ['Reactions', num(summary.reactions), `${num(summary.reactions7)} on last-7-d messages`],
  ['LLM calls', num(summary.llmCalls), ''],
  ['API cost (est.)', usd(summary.apiCost ?? 0), `+ ${usd(summary.subCost ?? 0)} API-equiv. on subscriptions`],
  ['Queue', num(summary.pending), `${num(summary.processing)} processing · ${num(summary.orphans)} orphans`],
  ['Silent sources', num(summary.silent), `regular sources quiet > ${SILENT_DAYS} d`],
  ['CLI', esc(summary.cli ?? '—'), 'health state from bot_state'],
]);
const nav = sections.map((s) => { const m = /id="(\w+)"><h2>([^<]+)/.exec(s); return `<a href="#${m[1]}">${m[2]}</a>`; }).join('');
const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>RssNewsBot DB report</title>
<style>
:root { color-scheme: light; --page:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e; --muted:#898781; --grid:#e1e0d9; --axis:#c3c2b7;
  --ring:rgba(11,11,11,.10); --bad:#d03b3b; --good:#006300; --warn:#a86b00;
  --s1:#2a78d6; --s2:#eb6834; --s3:#1baf7a; --s4:#eda100; --s5:#e87ba4; --s6:#008300; --s7:#4a3aa7; --s8:#e34948; }
@media (prefers-color-scheme: dark) { :root { color-scheme: dark; --page:#0d0d0d; --surface:#1a1a19; --ink:#fff; --ink2:#c3c2b7; --muted:#898781;
  --grid:#2c2c2a; --axis:#383835; --ring:rgba(255,255,255,.10); --bad:#e66767; --good:#0ca30c; --warn:#fab219;
  --s1:#3987e5; --s2:#d95926; --s3:#199e70; --s4:#c98500; --s5:#d55181; --s6:#008300; --s7:#9085e9; --s8:#e66767; } }
* { box-sizing: border-box; }
body { margin: 0; background: var(--page); color: var(--ink); font: 14px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
main { max-width: 1100px; margin: 0 auto; padding: 24px 16px 64px; }
h1 { font-size: 22px; margin: 0 0 4px; } h2 { font-size: 17px; margin: 0 0 8px; } h3 { font-size: 14px; margin: 18px 0 6px; color: var(--ink2); }
.meta, .intro, .note, .muted { color: var(--ink2); } .meta { overflow-wrap: anywhere; } .note { font-size: 12.5px; } .muted { font-size: 12px; }
nav { display: flex; flex-wrap: wrap; gap: 4px 14px; margin: 12px 0 18px; font-size: 13px; } nav a { color: var(--ink2); }
section { background: var(--surface); border: 1px solid var(--ring); border-radius: 10px; padding: 16px; margin: 14px 0; }
.tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; margin: 16px 0; }
.tile { background: var(--surface); border: 1px solid var(--ring); border-radius: 10px; padding: 10px 12px; }
.tl { font-size: 12px; color: var(--ink2); } .tv { font-size: 22px; font-weight: 600; margin: 2px 0; } .ts { font-size: 12px; color: var(--muted); }
.tw { overflow-x: auto; } table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { text-align: left; padding: 4px 8px; border-bottom: 1px solid var(--grid); vertical-align: top; }
th { font-weight: 600; color: var(--ink2); white-space: nowrap; } .n { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
td.bad { color: var(--bad); font-weight: 600; } td.good { color: var(--good); font-weight: 600; } td.warn { color: var(--warn); font-weight: 600; }
.empty { color: var(--muted); font-style: italic; } code { font-size: 12px; }
.badge { font-size: 11px; border: 1px solid var(--axis); border-radius: 6px; padding: 0 5px; color: var(--ink2); }
.chart { width: 100%; height: 220px; display: block; }
.chart .grid { stroke: var(--grid); stroke-width: 1; } .chart .axis { stroke: var(--axis); stroke-width: 1; }
.chart .tick { fill: var(--muted); font-size: 11px; } .chart rect { stroke: var(--surface); stroke-width: 1; }
.legend { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 12px; color: var(--ink2); margin-bottom: 4px; }
.legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 5px; vertical-align: -1px; }
${Array.from({ length: SLOTS }, (_, i) => `.s${i + 1} { fill: var(--s${i + 1}); } .legend i.s${i + 1} { background: var(--s${i + 1}); }`).join('\n')}
</style></head><body><main>
<h1>RssNewsBot DB report</h1>
<p class="meta">${esc(DB_PATH)} · window ${esc(SINCE.slice(0, 10))} → ${esc(NOW_S.slice(0, 16))} (${DAYS} d, local time) · ${esc(latest)}${archiveDir ? ` · archive: ${archiveFiles} file(s) from ${esc(archiveDir)}` : ''}</p>
<p class="note">${esc(RETENTION_NOTE)}</p>
${head}
<nav>${nav}</nav>
${sections.join('\n')}
<p class="note">Generated ${esc(fmtLocal(new Date()))} by bench/db-audit/report.mjs. Hover a bar for its value.</p>
</main></body></html>
`;
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, html);
db.close();
console.log(`wrote ${OUT} (${(html.length / 1024).toFixed(0)} KB, window ${SINCE.slice(0, 10)} → ${NOW_S.slice(0, 16)})`);
