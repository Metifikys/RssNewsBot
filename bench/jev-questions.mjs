// Shared question set + state shape for the Jev / Laya pre-filter benchmarks.
// ── Questions ───────────────────────────────────────────────────────────────
// Common coarse "kind" taxonomy. Every Step 1 prompt's hard-reject list maps onto the JUNK
// kinds; the NEWS kinds map onto the shortlist taxonomies. `other` is the required catch-all.
const KIND = {
  announcement:     'A new product, game, feature, model, service, mission or plan is officially announced or launched by its maker',
  release_date:     'A release/launch/landing date, delay, cancellation or availability change is confirmed',
  update_patch:     'An update, patch, version release, changelog or beta rollout of an existing product or software',
  policy_regulation:'Platform policy, terms, regulation, court ruling, lawsuit, antitrust, government decision or ban',
  business:         'Acquisition, funding, investment, layoffs, studio/company closure, earnings, market/sales figures, chip or cloud business deals',
  security_incident:'Security breach, vulnerability, outage, hack, data leak, scam or malware',
  rumor_leak:       'Unconfirmed rumor, leak, datamine, insider report or spotted listing about an upcoming product',
  research_result:  'A study, paper, experiment, discovery, mission result or measurement with a concrete finding',
  quote_interview:  'A named developer, executive or scientist says something notable; interview or statement with a concrete new claim',
  community_story:  'Viral community/player/user story, fan project, mod, speedrun, social media moment',
  review:           'A review, hands-on, preview impressions, benchmark or rating of a product, game, film or device',
  opinion_analysis: 'Opinion, editorial, column, essay, commentary, explainer, retrospective, or analysis without a new fact',
  guide_howto:      'Guide, tips, walkthrough, how-to, tutorial, buying guide, comparison or recommendation list',
  deal_promo:       'Deal, discount, sale, pre-order, bundle, giveaway, collector edition or promotional offer',
  listicle_roundup: 'Listicle, ranking, quiz, trivia, "best of", weekly roundup, on-this-day or anniversary post',
  entertainment_offtopic: 'Films, TV, celebrities, sport, politics or lifestyle with no direct tie to the channel topic',
  other:            'None of the above',
};

const SCOPE = {
  gaming:  'a curated Ukrainian Telegram digest about video games and the games industry (announcements, release dates, platform policy, industry news, credible rumors/leaks, major DLC, delays, lawsuits, acquisitions, studio news, notable patches, fun developer quotes, big community stories)',
  tech:    'a curated Ukrainian Telegram digest about technology (AI models/platforms, big tech, chips, cloud, browsers, OS, devtools, infrastructure, security incidents, outages, regulation, courts, consumer tech with real impact)',
  science: 'a curated Ukrainian popular-science Telegram digest (space missions, launches, astronomy, physics, biology, medical research, climate findings, retractions, science policy and funding)',
};
const JUNK = {
  gaming:  'reviews/previews of old games, late ports or minor DLC; opinion pieces, op-eds, editorials; guides, tips, tournament guides; physical/collector edition pre-orders; minor or cosmetic mods; film/TV casting without a game tie-in; esports match chatter; PR fluff; clickbait',
  tech:    'opinion, essays, columns, editorials, generic analysis without a new fact; product reviews, buying guides, hands-on impressions, comparisons; minor app updates, small UI tweaks, minor beta features; deals and discounts; SEO explainers, listicles, anniversaries, on-this-day posts, reposts',
  science: 'listicles, quizzes, trivia, buying guides, how-tos; product/gadget reviews, consumer tech or app news; health advice, wellness tips, nutrition hype; sponsored/PR fluff with no result; off-topic politics, business or sport; press releases that only say researchers are working on something with no result; sensational wording without a factual result',
};

export function questionsFor(category) {
  return {
    keep: {
      type: 'noul',
      instructions: `Is this article a genuine, timely news item that belongs in ${SCOPE[category]}? Judge the article itself, not its importance. Answer no for junk: ${JUNK[category]}.`,
      criteria: {
        true:  'A factual news item about a concrete new event, announcement, result, decision or credible report; suitable for an editorial news digest',
        false: 'Junk for this digest: opinion, review, guide, deal, listicle, PR fluff, clickbait, off-topic, or an item with no concrete new fact',
      },
    },
    kind: {
      type: 'choice',
      instructions: 'What kind of article is this? Pick the single best-fitting kind based on what the article actually is (its form), not what it mentions.',
      criteria: KIND,
    },
    fit: {
      type: 'score',
      instructions: `How well does this article fit ${SCOPE[category]}? Rate the item's suitability for a short curated digest.`,
      criteria: [
        'Not for the digest: opinion, review, guide, deal, listicle, off-topic, PR fluff or no new fact',
        'Weak: on-topic but minor, incremental, niche or thinly sourced',
        'Solid: an ordinary but sound news item readers of the digest would want',
        'Major: industry-moving, widely relevant or genuinely surprising news',
      ],
    },
  };
}

export function stateFor(item) {
  return {
    channel_topic: item.category,
    source_site: item.source,
    published: item.pubDate,
    title: item.title,
    article_text: item.text,
  };
}

