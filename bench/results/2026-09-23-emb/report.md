# Embedding junk-gate — logistic regression on text-embedding-3-small
train < 2026-08-20 ≤ val < 2026-08-24 ≤ test (by Step 1 decision time)

## tech
labeled with embedding: 14905 (junk 12325, kept 2580); train 8536, val 1775, test 4594 (test junk share 83.1%)

### emb: C=1, AUC val 0.919, **AUC test 0.898**
| target loss (val) | threshold | junk dropped (test) | kept lost (test) | share of Step 1 input removed |
|---|---|---|---|---|
| 1.0% | 0.167 | 47.4% | 1.5% | 39.7% |
| 2.0% | 0.208 | 53.0% | 2.6% | 44.5% |
| 4.0% | 0.297 | 63.4% | 5.4% | 53.6% |

### emb+host: C=1, AUC val 0.918, **AUC test 0.899**
| target loss (val) | threshold | junk dropped (test) | kept lost (test) | share of Step 1 input removed |
|---|---|---|---|---|
| 1.0% | 0.170 | 48.2% | 1.7% | 40.3% |
| 2.0% | 0.232 | 56.4% | 3.0% | 47.4% |
| 4.0% | 0.289 | 63.2% | 5.3% | 53.4% |

### vs Jev on the same 156 bench items in the test window (79 junk / 77 kept; bench is class-balanced)
AUC classifier (emb+host) **0.870** vs Jev keep **0.892**, average of both ranks 0.908

#### tech: kept by Step 1, lowest classifier score (would be lost first)
- 0.037 | Why Free Software usability tends to suck <web.archive.org>
- 0.103 | Read your own writes, off the primary <boringsql.com>
- 0.104 | Training a Misaligned Reward Seeker <alignment.anthropic.com>
- 0.106 | MNT Station: модульний міні-ПК в алюмінії, який можна зібрати під себе <gagadget.com>
- 0.110 | Anthropic: Can AI Models Judge AI Safety Research Proposals? <alignment.anthropic.com>
- 0.121 | How AI plotted an interstellar journey to Alpha Centauri <technologyreview.com>
- 0.122 | Windows 11 may finally let you turn off and restart your PC using your phone <xda-developers.com>
- 0.138 | 507 Mechanical Movements <507movements.com>
- 0.143 | The Internet Archive's Vintage AI Collection <archive.org>
- 0.147 | "Резерв+" без фото: чи дійсний документ і чи будуть проблеми з ТЦК <itc.ua>

#### tech: junk by Step 1, highest classifier score
- 0.981 | BotBase for Operators: A clearer path to joining Cloudflare's directory of bots and agents <blog.cloudflare.com>
- 0.970 |  ShinyHunters hackers claim to have hit data center provider used by Microsoft and Meta  <techradar.com>
- 0.957 |  US Navy launches missiles from its first drone sailboat — Saildrone Surveyor launches dual JAGM mis <tomshardware.com>
- 0.955 |  Claude nukes a developer's 700 GB home directory while testing deletion safeguards; automatic model <tomshardware.com>
- 0.954 | Broadcom pledges to lock down open source Python, Java libraries <theregister.com>
- 0.950 | DHS is using obscure law to snoop on journalists, non-profits, unions <theguardian.com>

## gaming
labeled with embedding: 5532 (junk 3416, kept 2116); train 3073, val 676, test 1783 (test junk share 59.2%)

### emb: C=10, AUC val 0.898, **AUC test 0.902**
| target loss (val) | threshold | junk dropped (test) | kept lost (test) | share of Step 1 input removed |
|---|---|---|---|---|
| 1.0% | 0.032 | 35.7% | 0.6% | 21.4% |
| 2.0% | 0.086 | 50.8% | 2.8% | 31.2% |
| 4.0% | 0.135 | 58.0% | 4.0% | 36.0% |

### emb+host: C=3, AUC val 0.901, **AUC test 0.907**
| target loss (val) | threshold | junk dropped (test) | kept lost (test) | share of Step 1 input removed |
|---|---|---|---|---|
| 1.0% | 0.093 | 43.6% | 1.4% | 26.4% |
| 2.0% | 0.147 | 51.6% | 2.2% | 31.5% |
| 4.0% | 0.220 | 60.6% | 3.3% | 37.2% |

### vs Jev on the same 157 bench items in the test window (75 junk / 82 kept; bench is class-balanced)
AUC classifier (emb+host) **0.945** vs Jev keep **0.887**, average of both ranks 0.942

#### gaming: kept by Step 1, lowest classifier score (would be lost first)
- 0.037 | The Smallest Tamagotchi Yet Lives Inside A One Inch Screen On Your Finger <kotaku.com>
- 0.043 |  Marvel's Wolverine pays direct tribute to X-Men: The Animated Series' most enduring meme  <gamesradar.com>
- 0.051 | 33 Years Later, Magic’s Head Designer Reveals One of His Biggest Regrets <polygon.com>
- 0.052 | Pokemon Pokopia Bubbly Basin DLC Expands Lore and Catalog <siliconera.com>
- 0.055 | Exclusive: Magic Designers Reveal Why Secret Lair's New Zeta Set Had to Come in Booster Packs <polygon.com>
- 0.068 | Sentinel Shell Cinematic | Marathon <youtu.be>
- 0.069 | Review: Ledgerbound Is a Romantic Comedy With a Little Strategy, as a Treat <siliconera.com>
- 0.074 | Indiana Jones and the Last Crusade: 80s Anti-Piracy Nightmare - Modern Vintage Gamer <youtube.com>
- 0.081 | Cairn - On The Trail (new trailer) <youtu.be>
- 0.092 | Alien Geoguessr Meets Sherlock Holmes in This Captivating Steam Mystery <polygon.com>

#### gaming: junk by Step 1, highest classifier score
- 0.974 | Arcade Archives Satan of Saturn launches August 27 <gematsu.com>
- 0.968 | ANOMALITH physical edition for PS5 launches November 12 in the west <gematsu.com>
- 0.961 | Indie Developer Has Equipment Stolen From Gamescom Booth <insider-gaming.com>
- 0.952 | New Studio Charging AAA Prices is this the new normal? Blood of Dawnwalker's price is crazy <reddit.com>
- 0.948 | GTA 6 Leaks Have Given One Indie Game A New Marketing Tactic <thegamer.com>
- 0.935 | GTA 6 May Be Set in Vice City, but It Has San Andreas’ Ambition <ign.com>
