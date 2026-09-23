// Merge the bot's retention archive (`archive.enabled`, files `news-archive-<period>.db`) into a
// news.db session, so a bench sees more history than the 14–90-day retention windows keep.
//
//   import { mergeArchive, takeArchiveFlag } from './lib/archive.mjs';
//   const archiveDir = takeArchiveFlag(process.argv);          // strips `--archive <dir>` from argv
//   if (archiveDir) mergeArchive(db, archiveDir, ['articles', 'covered_events', 'rejected_events']);
//
// For every requested table a TEMP table of the same name is built from the main rows plus each
// archive file's rows (a row whose key is already present wins from main / the earlier file).
// TEMP shadows main for unqualified names, so a script's SQL runs unchanged over the merged
// history. Files are attached one at a time, so SQLite's 10-attachment limit never applies.
// Works on a readOnly connection (the temp schema is separate).
import { readdirSync } from 'node:fs';
import { join } from 'node:path';

// Dedup key per table (null = no key: rows are appended as-is) and the join indexes the benches need.
const KEYS = {
  articles: 'id', article_embeddings: 'article_id', event_embeddings: 'id', rejected_events: 'id',
  covered_events: 'id', llm_calls: 'id', summaries: 'id', digest_messages: null, reaction_counts: null,
};
const INDEXES = {
  articles: ['id', 'link'], article_embeddings: ['article_id'], covered_events: ['url'], rejected_events: ['url'],
};

const q = (id) => `"${String(id).replace(/"/g, '""')}"`;

/** Removes `--archive <dir>` from argv (in place) and returns the dir, or null when absent. */
export function takeArchiveFlag(argv) {
  const i = argv.indexOf('--archive');
  if (i < 0) return null;
  const [, dir] = argv.splice(i, 2);
  if (!dir) throw new Error('--archive needs a directory');
  return dir;
}

/** Builds TEMP copies of `tables` = main + every archive file in `dir`. Returns the file count. */
export function mergeArchive(db, dir, tables) {
  const files = readdirSync(dir).filter((f) => /^news-archive-.+\.db$/.test(f)).sort();
  const columns = (schema, t) => db.prepare(`PRAGMA ${schema}.table_info(${q(t)})`).all().map((c) => c.name);
  for (const t of tables) {
    if (!(t in KEYS)) throw new Error(`mergeArchive: unknown table ${t}`);
    const mainCols = columns('main', t);
    const list = mainCols.map(q).join(', ');
    db.exec(`CREATE TEMP TABLE ${q(t)} AS SELECT ${list} FROM main.${q(t)}`);
    for (const c of INDEXES[t] ?? []) db.exec(`CREATE INDEX temp.${q(`ix_${t}_${c}`)} ON ${q(t)} (${q(c)})`);
    const key = KEYS[t];
    for (const f of files) {
      db.prepare('ATTACH DATABASE ? AS arch').run(join(dir, f));
      try {
        const have = new Set(columns('arch', t));
        if (have.size === 0) continue;   // this period archived other tables only
        const select = mainCols.map((c) => (have.has(c) ? q(c) : 'NULL')).join(', ');
        const dedup = key ? ` WHERE ${q(key)} NOT IN (SELECT ${q(key)} FROM temp.${q(t)})` : '';
        db.exec(`INSERT INTO temp.${q(t)} (${list}) SELECT ${select} FROM arch.${q(t)}${dedup}`);
      } finally {
        db.exec('DETACH DATABASE arch');
      }
    }
  }
  return files.length;
}
