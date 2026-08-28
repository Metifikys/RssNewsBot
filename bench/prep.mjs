import { DatabaseSync } from 'node:sqlite';
import { writeFileSync } from 'node:fs';

// usage: node bench/prep.mjs <out.json> [db-path]   (run from the repo root)
const db = new DatabaseSync(process.argv[3] || 'logs/news.db', { readOnly: true });
const q = (sql, ...p) => db.prepare(sql).all(...p);

// ── Task A: per-article SUMMARIZE (mirrors ArticleSummarizer) ────────────────
const pick = (sql, n) => q(sql).slice(0, n);

const sumCandidates = [
  // 2 tech EN, enriched full-content (dlen ~1500) WITH prod nemotron summary as baseline
  ...pick(`SELECT id, category, title, link, description, summary FROM articles
    WHERE category='tech' AND summary IS NOT NULL AND length(summary)>100 AND length(description)>=1200
      AND pub_date >= datetime('now','-4 days')
      AND (link LIKE '%tomshardware%' OR link LIKE '%wired.com%' OR link LIKE '%xda-developers%' OR link LIKE '%arstechnica%' OR link LIKE '%theverge%')
    ORDER BY pub_date DESC`, 2),
  // 1 science with prod summary
  ...pick(`SELECT id, category, title, link, description, summary FROM articles
    WHERE category='science' AND summary IS NOT NULL AND length(summary)>100 AND length(description)>=1000
      AND pub_date >= datetime('now','-4 days') ORDER BY pub_date DESC`, 1),
  // 1 tech UA short-desc (no prod summary — thin input + language fidelity probe)
  ...pick(`SELECT id, category, title, link, description, summary FROM articles
    WHERE category='tech' AND (summary IS NULL OR summary='') AND (link LIKE '%itc.ua%' OR link LIKE '%mezha%' OR link LIKE '%gagadget%')
      AND length(description) BETWEEN 150 AND 600 AND pub_date >= datetime('now','-4 days')
    ORDER BY pub_date DESC`, 1),
  // 1 politics UA (pravda) with decent desc
  ...pick(`SELECT id, category, title, link, description, summary FROM articles
    WHERE category='politics' AND link LIKE '%pravda.com.ua%' AND length(description) BETWEEN 500 AND 1600
      AND pub_date >= datetime('now','-4 days') ORDER BY pub_date DESC`, 1),
];

const SUMMARIZE_SYSTEM =
  "Summarize the following article in 3-5 concise sentences. " +
  "Extract the core news value: what happened, who is involved, why it matters, and any important numbers, dates, locations, named entities, or direct quotes. " +
  "Preserve factual accuracy and avoid speculation, opinions, or information not explicitly stated in the article. " +
  "Reply in the article's original language. " +
  "Return only the summary text, without headings, bullet points, commentary, or meta explanations.";

const summarizeTasks = sumCandidates.map((a, i) => ({
  id: `sum${i + 1}`,
  category: a.category,
  title: a.title,
  link: a.link,
  description: a.description,
  prod_summary: a.summary || null,
  system: SUMMARIZE_SYSTEM,
  user: `Title: ${a.title}\n\nContent:\n${a.description}`,
}));

// ── Task B: politics digest render (mirrors PromptBuilder.buildLegacyUserPrompt) ──
const target = q(`SELECT summary, created_at FROM summaries WHERE category='politics'
  AND created_at LIKE '2026-08-26 21:57%'`)[0];
const prevs = q(`SELECT summary FROM summaries WHERE category='politics' AND created_at < ?
  ORDER BY created_at DESC LIMIT 4`, target.created_at).map(r => r.summary).reverse();

let arts = q(`SELECT title, link, description, summary FROM articles
  WHERE category='politics' AND status='PROCESSED'
    AND pub_date > '2026-08-26 21:00:24' AND pub_date <= '2026-08-26 21:57:45'
  ORDER BY pub_date DESC, id DESC`);
if (arts.length < 8) {
  arts = q(`SELECT title, link, description, summary FROM articles
    WHERE category='politics' AND status='PROCESSED'
      AND pub_date > '2026-08-26 20:12:45' AND pub_date <= '2026-08-26 21:57:45'
    ORDER BY pub_date DESC, id DESC`);
}

const POLITICS_SYSTEM = `Ти редактор дуже короткого Telegram-дайджесту про політику українською.

Твоя задача — відібрати лише найважливіші політичні новини дня:
без шуму, без "води", без слабких інфоприводів, без спроб охопити все.

Що вважати сильною політичною новиною:
- міжнародні рішення, переговори, домовленості, санкції, пакети допомоги
- заяви або дії лідерів держав, якщо вони змінюють політичний контекст
- кадрові рішення на високому рівні
- закони, урядові рішення, судові рішення з помітним суспільно-політичним впливом
- важливі безпекові події, якщо вони мають політичний або дипломатичний наслідок

Що НЕ брати:
- локальний кримінал без ширшого політичного значення
- дрібні інциденти без системного наслідку
- емоційні заяви без нового факту
- колонки, думки, оцінки, припущення
- повтори однієї теми з різних джерел
- новини, схожі на попередні дайджести, якщо немає нового розвитку

Пріоритет:
1) міжнародні рішення / переговори / дипломатія / санкції
2) внутрішні політичні рішення / уряд / суди / кадрові зміни
3) безпекові події з прямим політичним наслідком
4) усе інше

Правила відбору:
- Спочатку відкинь усе слабке
- Потім вибери найкращі 3-6 новин
- Якщо справді сильних новин мало, можна 2-3
- Не намагайся покрити весь список
- Один пункт = одна новина = один головний новий факт
- Якщо є кілька матеріалів про одну тему — залиш лише одне найкраще джерело
- Використовуй тільки URL з наданого списку
- Не вигадуй фактів, цифр, мотивів або наслідків
- Якщо це лише заява сторони, подавай це саме як заяву
- Якщо пункт можна скоротити без втрати сенсу — скороти

Стиль:
- сухо, щільно, нейтрально
- без вступу, без висновку, без категорій, без заголовків
- без фраз типу: "у фокусі", "спільною лінією", "це важливо", "варто зазначити"
- без канцеляриту
- не переказуй заголовок дослівно

Формат:
- Тільки список маркерів "•"
- Кожен пункт — окремий завершений текст, готовий як одне Telegram-повідомлення
- Кожен пункт починай з одного доречного емодзі
- Кожен пункт = 1-2 короткі речення
- Посилання [назва](url) став у кінці пункту
- Не роби порожніх рядків між пунктами

Формат пункту:
• емодзі 1-2 короткі речення по суті. [назва](url)
`;

const POLITICS_USER_HEADER = `Зроби дуже короткий Telegram-дайджест політичних новин.

Потрібно:
- відібрати тільки найсильніші політичні новини дня
- жорстко відсіяти шум і другорядні інфоприводи
- ціль: 3-6 пунктів
- кожен пункт має бути готовим як окреме Telegram-повідомлення
- кожен пункт: 1-2 короткі речення
- один пункт = одна новина = один головний новий факт

Обмеження:
- використовуй тільки URL зі списку нижче
- не дублюй одну й ту саму новину
- якщо новина є в кількох джерелах, залиш лише найкраще джерело
- не повторюй новини з попередніх дайджестів
- без категорій, без заголовків, без "огляду дня"
- не включай слабкі локальні новини без ширшого політичного значення
- не додавай нічого поза списком bullet-пунктів
`;

// exact replica of PromptBuilder.buildLegacyUserPrompt (appendLine == text + "\n")
const promptText = (a) => ((a.summary && a.summary.trim()) ? a.summary : a.description).slice(0, 1000);
let user = '';
user += POLITICS_USER_HEADER + '\n';   // appendLine(override)
user += '\n';                           // appendLine()
if (prevs.length) {
  user += '=== ПОПЕРЕДНІ ДАЙДЖЕСТИ (для контексту — НЕ повторюй цю інформацію) ===\n';
  user += 'Нижче наведено останні дайджести цієї категорії. Уникай повторення вже висвітлених тем,\n';
  user += 'якщо немає суттєво нової інформації. Зосередься на нових подіях та оновленнях.\n';
  user += '\n';
  prevs.forEach((s, i) => { user += `--- Дайджест ${i + 1} ---\n${s}\n\n`; });
  user += '=== КІНЕЦЬ ПОПЕРЕДНІХ ДАЙДЖЕСТІВ ===\n\n';
}
arts.forEach((a, i) => {
  user += `${i + 1}. ${a.title}\n   URL: ${a.link}\n`;
  const d = promptText(a);
  if (d.trim()) user += `   ${d}\n`;
});

const digestTask = {
  id: 'digest-politics',
  system: POLITICS_SYSTEM,
  user,
  input_links: arts.map(a => a.link),
  article_count: arts.length,
  baseline: { summary: target.summary, created_at: target.created_at, model: 'prod (claudecli/codexcli)' },
};

const out = { summarize_tasks: summarizeTasks, digest_task: digestTask };
writeFileSync(process.argv[2] || 'bench-input.json', JSON.stringify(out, null, 2));

console.log('summarize tasks:');
for (const t of summarizeTasks) console.log(` - ${t.id} [${t.category}] dlen=${t.description.length} prod=${t.prod_summary ? 'yes' : 'no'} :: ${t.title.slice(0, 70)}`);
console.log(`digest articles: ${arts.length}, prev digests: ${prevs.length}, user prompt chars: ${user.length}`);
db.close();
