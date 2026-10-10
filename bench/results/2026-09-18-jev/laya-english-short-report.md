# Jev pre-filter benchmark — 1500 answered items
total cost $0.0000, latency p50 20ms p90 28ms, avg input tokens 634

## Separation (AUC: covered above rejected; 0.5 = coin flip)
| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |
|---|---|---|---|---|
| gaming | 250 / 250 | 0.658 | 0.534 | 0.998 |
| tech | 250 / 250 | 0.723 | 0.632 | 0.999 |
| science | 250 / 250 | 0.670 | 0.674 | 1.000 |
| ALL | 750 / 750 | 0.681 | 0.613 | 0.998 |

### keep — drop when keep < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.05 | 6.0% (15/250) | 0.4% (1/250) | 93.8% |
| gaming | 0.1 | 14.8% (37/250) | 5.2% (13/250) | 74.0% |
| gaming | 0.2 | 35.2% (88/250) | 18.0% (45/250) | 66.2% |
| gaming | 0.3 | 45.6% (114/250) | 22.0% (55/250) | 67.5% |
| gaming | 0.5 | 56.8% (142/250) | 32.8% (82/250) | 63.4% |
| tech | 0.05 | 3.6% (9/250) | 2.0% (5/250) | 64.3% |
| tech | 0.1 | 7.2% (18/250) | 3.6% (9/250) | 66.7% |
| tech | 0.2 | 24.8% (62/250) | 10.0% (25/250) | 71.3% |
| tech | 0.3 | 37.2% (93/250) | 12.4% (31/250) | 75.0% |
| tech | 0.5 | 52.4% (131/250) | 18.4% (46/250) | 74.0% |
| science | 0.05 | 3.6% (9/250) | 1.2% (3/250) | 75.0% |
| science | 0.1 | 10.8% (27/250) | 3.2% (8/250) | 77.1% |
| science | 0.2 | 32.4% (81/250) | 11.2% (28/250) | 74.3% |
| science | 0.3 | 47.2% (118/250) | 18.0% (45/250) | 72.4% |
| science | 0.5 | 56.4% (141/250) | 29.6% (74/250) | 65.6% |

### fit — drop when fit < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.3 | 0.4% (1/250) | 0.0% (0/250) | 100.0% |
| gaming | 0.5 | 0.4% (1/250) | 0.4% (1/250) | 50.0% |
| gaming | 0.75 | 0.8% (2/250) | 0.4% (1/250) | 66.7% |
| gaming | 1 | 2.0% (5/250) | 0.8% (2/250) | 71.4% |
| tech | 0.3 | 1.6% (4/250) | 0.0% (0/250) | 100.0% |
| tech | 0.5 | 1.6% (4/250) | 0.0% (0/250) | 100.0% |
| tech | 0.75 | 3.2% (8/250) | 0.4% (1/250) | 88.9% |
| tech | 1 | 4.4% (11/250) | 1.6% (4/250) | 73.3% |
| science | 0.3 | 0.8% (2/250) | 0.0% (0/250) | 100.0% |
| science | 0.5 | 1.6% (4/250) | 0.0% (0/250) | 100.0% |
| science | 0.75 | 2.4% (6/250) | 0.0% (0/250) | 100.0% |
| science | 1 | 2.8% (7/250) | 0.0% (0/250) | 100.0% |

### combined — drop when keep < 0.2 AND fit < 0.75
| category | junk dropped | lost news | precision |
|---|---|---|---|
| gaming | 0.8% | 0.0% | 100.0% |
| tech | 2.4% | 0.4% | 85.7% |
| science | 1.6% | 0.0% | 100.0% |

## Kind distribution (Jev) by Step 1 verdict

### gaming
| kind | rejected | covered |
|---|---|---|
| news | 65 | 151 |
| review | 58 | 49 |
| opinion | 35 | 19 |
| other | 26 | 21 |
| guide | 36 | 1 |
| deal | 18 | 9 |
| listicle | 12 | 0 |

### tech
| kind | rejected | covered |
|---|---|---|
| news | 60 | 117 |
| review | 72 | 21 |
| deal | 30 | 58 |
| other | 30 | 40 |
| guide | 25 | 11 |
| opinion | 24 | 2 |
| listicle | 9 | 1 |

### science
| kind | rejected | covered |
|---|---|---|
| news | 115 | 164 |
| review | 46 | 35 |
| opinion | 41 | 32 |
| other | 28 | 12 |
| guide | 12 | 0 |
| deal | 4 | 6 |
| listicle | 4 | 1 |

## Disagreements worth eyeballing

### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops
- [gaming] keep=0.0699 fit=1.7671 kind=opinion | step1 dev_quote nw=6 fit=8 | Simpsons Creator Says Hit & Run Is Returning, Showrunner Says Otherwise <https://www.thegamer.com/the-simpsons-creator-hit-and-run-remake>
- [gaming] keep=0.0763 fit=1.4272 kind=deal | step1 dev_quote nw=7 fit=8 |  GTA 6 doesn't feature microtransactions or AI, Rockstar says, but I doubt its take on GTA <https://www.gamesradar.com/games/grand-theft-auto/gta-6-doesnt-feature-microtransactions-or-ai-rockstar-says-but-i-doubt-its-take-on-gta-online-wont-have-shark-cards>
- [gaming] keep=0.0998 fit=1.5666 kind=other | step1 dev_quote nw=6 fit=8 |  Kingdom Hearts 4 lead Tetsuya Nomura plays 4D chess, potentially drops hints his canceled <https://www.gamesradar.com/games/kingdom-hearts/kingdom-hearts-4-lead-tetsuya-nomura-plays-4d-chess-potentially-drops-hints-his-canceled-final-fantasy-game-could-somehow-connect-to-the-next-rpg>
- [science] keep=0.0314 fit=2.0539 kind=news | step1 correction_retraction nw=9 fit=8 | Major COVID study that fueled vaccine skepticism retracted over 'misinformation' <https://www.scientificamerican.com/article/major-covid-study-that-fueled-vaccine-skepticism-retracted-over-misinformation>
- [science] keep=0.0844 fit=2.3461 kind=review | step1 archaeology_paleontology nw=7 fit=8 | Ancient South American families built massive waterways by themselves <https://www.popsci.com/environment/ancient-south-america-raised-waterways>
- [gaming] keep=0.0563 fit=2.0838 kind=deal | step1 dev_quote nw=5 fit=7 |  Dragon Quest 9 has been trapped on the Nintendo DS for 17 years, and franchise creator Yu <https://www.gamesradar.com/games/dragon-quest/dragon-quest-9-has-been-trapped-on-the-nintendo-ds-for-17-years-and-franchise-creator-yuji-horii-was-a-little-disheartened-to-learn-fans-never-wanted-that>
- [gaming] keep=0.0964 fit=1.4485 kind=review | step1 dev_quote nw=5 fit=7 |  Clair Obscur: Expedition 33's Clea actor finds it "fascinating" that RPG fans give her ch <https://www.gamesradar.com/games/baldur-s-gate/clair-obscur-expedition-33s-clea-actor-finds-it-fascinating-that-rpg-fans-give-her-character-more-grace-than-baldurs-gate-3s-laezel>
- [gaming] keep=0.0987 fit=1.5053 kind=other | step1 dev_quote nw=5 fit=7 |  "Just pirate it": Roguelike dev would rather you download its games illegally than buy fr <https://www.gamesradar.com/games/just-pirate-it-roguelike-dev-would-rather-you-download-its-games-illegally-than-buy-from-steam-code-resellers>
- [gaming] keep=0.0451 fit=1.3652 kind=other | step1 dev_quote nw=5 fit=7 |  "I'm a little disappointed with Rockstar": GTA 5 and Red Dead Redemption actor auditioned <https://www.gamesradar.com/games/grand-theft-auto/im-a-little-disappointed-with-rockstar-gta-5-and-red-dead-redemption-actor-auditioned-for-over-60-roles-in-gta-6-and-never-heard-back>
- [gaming] keep=0.0979 fit=2.0174 kind=review | step1 community_story nw=6 fit=7 |  Pocketpair lead thought if Palworld 1.0 hit 200k concurrent players, "that'd be party tim <https://www.gamesradar.com/games/survival/pocketpair-lead-thought-if-palworld-1-0-hit-200k-concurrent-players-thatd-be-party-time-then-it-passed-900k-on-steam-alone>
- [gaming] keep=0.0805 fit=1.4741 kind=opinion | step1 dev_quote nw=5 fit=7 |  Dragon Age was always "too niche, too backwards, too old-school" in EA's eyes, former Bio <https://www.gamesradar.com/games/dragon-age/dragon-age-was-always-too-niche-too-backwards-too-old-school-in-eas-eyes-former-bioware-lead-argues>
- [tech] keep=0.0779 fit=1.6446 kind=other | step1 devtool_release nw=5 fit=7 | added day-1 mlx-lm support for meta's muse glimmer 30b (PR up) <https://www.reddit.com/r/LocalLLaMA/comments/1vldpx8/added_day1_mlxlm_support_for_metas_muse_glimmer>
- [tech] keep=0.0826 fit=1.6396 kind=deal | step1 fraud nw=6 fit=7 | Компанія, що пропонує медичні дослідження, 100% виконані людьми, виявилася цілковитою ШІ-п <https://mezha.ua/news/research-gold-ai-314093>
- [tech] keep=0.0002 fit=1.1228 kind=other | step1 government_tech_deployment nw=7 fit=7 | Може долітати до Сибіру: МОУ кодифікувало безпілотник дальністю понад 2 тис. км <https://oboronka.mezha.ua/mou-kodifikuvalo-dalekobiyniy-bpla-dovbush-t40-314256>
- [science] keep=0.0662 fit=1.8907 kind=other | step1 archaeology_paleontology nw=7 fit=7 | 15,000 years ago, desert dwellers dined on snakes and lizards <https://www.popsci.com/science/snake-lizard-diet-natufians-levant>
- [science] keep=0.0899 fit=1.8687 kind=other | step1 archaeology_paleontology nw=6 fit=7 | Indigenous nations in present-day Georgia may have enjoyed cacao <https://www.popsci.com/science/indigenous-cacao-georgia>
- [science] keep=0.0899 fit=2.1795 kind=news | step1 safety_risk nw=8 fit=7 | Cyclospora outbreak tied to two deaths as parasite-borne illness continues to rage <https://www.scientificamerican.com/article/cyclospora-outbreak-tied-to-two-deaths-as-the-parasite-borne-illness-continues-to-rage>
- [science] keep=0.0025 fit=1.7446 kind=other | step1 archaeology_paleontology nw=6 fit=7 | Two-headed snake pottery suggests ancient Roman mystery cult spanned over 2,000 miles <https://www.popsci.com/science/ancient-roman-snake-cult-spain>
- [gaming] keep=0.0883 fit=1.8784 kind=news | step1 dev_quote nw=5 fit=6 |  Kratos actor Christopher Judge says he read for the God of War TV show, but thinks Dave B <https://www.gamesradar.com/entertainment/fantasy-shows/kratos-actor-christopher-judge-says-he-read-for-the-god-of-war-tv-show-but-thinks-dave-bautista-is-going-to-be-great>
- [gaming] keep=0.0884 fit=1.5758 kind=deal | step1 industry_news nw=6 fit=6 |  PlayStation is getting "most" of Sony's estimated $508 million tariff refund, but despite <https://www.gamesradar.com/platforms/playstation/playstation-is-getting-most-of-sonys-estimated-usd508-million-tariff-refund-but-despite-lawsuits-there-are-no-apparent-plans-to-give-refunds-for-price-hiked-ps5s>
- [tech] keep=0.0763 fit=1.3383 kind=deal | step1 defense_tech_update nw=6 fit=6 | Британці відправлять на навчання НАТО автономне судно, здатне працювати понад 50 днів <https://oboronka.mezha.ua/britanskiy-oceanus17-virushit-na-navchannya-nato-314324>
- [tech] keep=0.0004 fit=1.334 kind=deal | step1 regulation nw=8 fit=6 | General Motors знаходиться під розслідуванням через масові звернення про відмову гальм у а <https://mezha.ua/news/v-ssha-rozsliduyut-masovi-vidmovi-galm-u-avtomobilyah-general-motors-314476>
- [science] keep=0.0001 fit=2.2266 kind=news | step1 policy_decision nw=7 fit=6 | Trump CDC pick Erica Schwartz confirmed to lead nation’s top public health agency <https://www.scientificamerican.com/article/trump-cdc-pick-erica-schwartz-confirmed-to-lead-nations-top-public-health-agency>
- [gaming] keep=0.0542 fit=0.9642 kind=other | step1 industry_news nw=5 fit=5 | Atelier Resleriana: Forgotten Alchemy and the Polar Night Liberator shuts down in Japan on <https://www.rpgsite.net/news/21219-atelier-resleriana-forgotten-alchemy-polar-night-liberator-shuts-down-in-japan-on-november-25>
- [gaming] keep=0.0616 fit=1.1847 kind=review | step1 industry_news nw=5 fit=5 |  We've been tracking graphics card prices for exactly one year and it's never been worse t <https://www.pcgamer.com/hardware/graphics-cards/weve-been-tracking-graphics-card-prices-for-exactly-one-year-and-its-never-been-worse-than-this>
- [tech] keep=0.0677 fit=1.3889 kind=other | step1 tech_history nw=2 fit=5 | Developer ported Word for Windows 1.1a from 1990 to run natively on Windows 11 <https://www.reddit.com/r/technology/comments/1vizqrn/developer_ported_word_for_windows_11a_from_1990>
- [tech] keep=0.0009 fit=1.5317 kind=deal | step1 defense_partnership nw=5 fit=5 | "Генерал Черешня" планує виробляти дрони спільно із фінською Patria <https://oboronka.mezha.ua/general-chereshnya-robitime-droni-u-finlyandiji-314435>
- [tech] keep=0.0345 fit=1.5721 kind=other | step1 historical_archive nw=2 fit=5 | ‘Farewell, Etaoin Shrdlu’ <https://archive.org/details/FarewellEtaoinShrdlu>
- [tech] keep=0.0305 fit=0.8873 kind=deal | step1 defense_tech_update nw=5 fit=5 | Нацгвардія отримала першу партію бронеавтомобілів "Варта-2" <https://oboronka.mezha.ua/ngu-pershu-partiyu-broneavtomobiliv-varta-2-314402>
- [science] keep=0.0903 fit=1.9681 kind=review | step1 correction_retraction nw=5 fit=5 | Author Correction: Uncovering the role of LINE-1 in the evolution of lung adenocarcinoma <https://www.nature.com/articles/s41586-026-10947-0>

### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)
- [gaming] keep=0.9251 fit=2.2717 kind=news | step1 minor_mod_release nw=2 fit=2 | You Can Now Play Driveclub at 1440p, 4K, 8K & 60FPS, Only on PC <https://www.dsogaming.com/news/you-can-now-play-driveclub-at-1440p-4k-8k-60fps-only-on-pc>
- [gaming] keep=0.9497 fit=1.988 kind=news | step1 physical_edition_preorder nw=3 fit=2 | Mewgenics PS5 and Nintendo Switch 2 physical release pre-orders are now live, full game on <https://www.rpgsite.net/news/21263-mewgenics-ps5-nintendo-switch-2-physical-release-pre-orders-are-now-live-full-game-on-cart>
- [gaming] keep=0.9326 fit=2.0453 kind=news | step1 release_date nw=2 fit=2 | Iron Nest: Heavy Turret Simulator release countdown — Exact date and time <https://www.destructoid.com/iron-nest-heavy-turret-simulator-release-countdown-exact-date-and-time>
- [gaming] keep=0.9118 fit=2.0329 kind=news | step1 community_story nw=3 fit=2 | Armao Leaves LYON With Inspired Expected To Make LCS Return <https://insider-gaming.com/armao-leaves-lyon-with-inspired-expected-to-make-lcs-return>
- [gaming] keep=0.9288 fit=2.4383 kind=news | step1 industry_news nw=2 fit=2 | This Week’s Japanese Game Releases: Starsand Island, STEINS;GATE RE:BOOT, more <https://www.gematsu.com/2026/08/this-weeks-japanese-game-releases-starsand-island-steinsgate-reboot-more>
- [gaming] keep=0.9717 fit=2.2858 kind=news | step1 indie_self_promo nw=2 fit=2 | Astronomics - Numizmatic - Intercept passing asteroids and automate resource mining - New  <https://www.reddit.com/r/Games/comments/1w2bpz4/astronomics_numizmatic_intercept_passing>
- [gaming] keep=0.9092 fit=2.4059 kind=news | step1 industry_news nw=2 fit=2 |  Secretlab launches folding armrests for the Titan Evo, allowing guitar players and neat f <https://www.gamesradar.com/hardware/chairs/secretlab-launches-folding-armrests-for-the-titan-evo-allowing-guitar-players-and-neat-freaks-to-modify-their-gaming-chair-to-suit-their-lifestyle>
- [gaming] keep=0.9105 fit=2.1267 kind=review | step1 major_announcement nw=3 fit=2 | The Severed Gods - Topebox - turn-based roguelite where you severed giants <https://www.reddit.com/r/Games/comments/1w2l2ei/the_severed_gods_topebox_turnbased_roguelite>
- [gaming] keep=0.9226 fit=2.1447 kind=review | step1 collectors_edition_announcement nw=3 fit=3 | Supreme Reveals 24K Gold Analogue Pocket Handhelds <https://www.polygon.com/game-boy-analogue-pocket-supreme-release-date>
- [tech] keep=0.9209 fit=1.996 kind=review | step1 ai_product_launch nw=2 fit=2 | local.ai early access <https://www.reddit.com/r/LocalLLaMA/comments/1vgxl53/localai_early_access>
- [tech] keep=0.9042 fit=2.3073 kind=guide | step1 ai_policy_regulation nw=2 fit=2 | Thomson Reuters Standard for High Stakes AI <https://www.thomsonreuters.com/en-us/posts/innovation/thomson-reuters-standard-for-high-stakes-ai>
- [tech] keep=0.9303 fit=2.0627 kind=news | step1 unverified_claim nw=2 fit=2 | Discord handing over user data to Take Two so they can hunt down the GTA 6 leaker <https://xcancel.com/Pagan_GMR/status/2091193953282293919>
- [tech] keep=0.9911 fit=2.3488 kind=news | step1 ai_product_launch nw=1 fit=1 | Topagent.lol – an AI agent leaderboard ranked purely by who paid most <https://topagent.lol>
- [tech] keep=0.9333 fit=2.0767 kind=deal | step1 shopping_guide nw=0 fit=0 | Аксесуари для студента 2026: що купити до навчального сезону <https://itc.ua/ua/novini/aksesuary-dlya-studenta-2026-shho-kupyty-do-navchalnogo-sezonu>
- [tech] keep=0.9231 fit=2.2368 kind=guide | step1 guide nw=1 fit=1 | I built a Spotify killer that fills in my self-hosted music library automatically <https://www.xda-developers.com/self-hosted-spotify-download-navidrome-free-youtube>
- [tech] keep=0.9606 fit=2.7054 kind=news | step1 analysis nw=0 fit=1 | Move 37 Is the Moment AI Changes Everything. It's Suddenly Happening Everywhere <https://www.wsj.com/tech/ai/move-37-ai-demis-hassabis-google-deepmind-alphago-ec832a41>
- [tech] keep=0.9532 fit=2.364 kind=review | step1 product_launch nw=0 fit=0 |  ‘The most eye-watering thing I’ve seen since the solar eclipse’ — experiencing the MSI Cl <https://www.techradar.com/computing/gaming-computers/msi-claw-8-ex-ai>
- [tech] keep=0.9251 fit=1.7918 kind=review | step1 research_breakthrough nw=2 fit=2 | Students prefer Gemini over ChatGPT and Claude for AI essays in blind tests <https://studyarena.com/blog/chatgpt-vs-claude-vs-gemini-college-essays-2026>
- [tech] keep=0.9519 fit=2.5202 kind=news | step1 ai_product_launch nw=2 fit=2 |  ‘It’s not about who can generate a homepage in 30 seconds’: Hostinger launches a new agen <https://www.techradar.com/pro/its-not-about-who-can-generate-a-homepage-in-30-seconds-hostinger-launches-a-new-agentic-ai-builder-helping-businesses-do-more-with-ai>
- [tech] keep=0.941 fit=2.3289 kind=guide | step1 research_breakthrough nw=3 fit=3 | AI is learning clinical judgment by practicing on simulated patients <https://www.echohive.ai/ai-clinical-residency>
- [tech] keep=0.9403 fit=2.0972 kind=news | step1 product_launch nw=3 fit=2 | As electric two-wheelers gain a foothold, Belgian startup Any bets on cargo space <https://techcrunch.com/2026/08/28/as-electric-two-wheelers-gain-a-foothold-belgian-startup-any-bets-on-cargo-space>
- [tech] keep=0.9089 fit=2.3089 kind=news | step1 leak nw=2 fit=1 |  Catastrophic GTA VI leak is a full working build — notorious hacker CyberLeek taunts Rock <https://www.tomshardware.com/video-games/catastrophic-gta-vi-leak-is-a-full-working-build-notorious-hacker-cyberleek-taunts-rockstar-games-by-spraying-the-word-leek-onto-a-wall-in-game-with-bullets>
- [tech] keep=0.9173 fit=2.4487 kind=news | step1 developer_platform_change nw=2 fit=3 | Show HN: Amendable – on-demand Git repos for AI agents <https://amendable.io>
- [science] keep=0.9369 fit=1.9058 kind=news | step1 routine_iss_activity nw=1 fit=1 | NASA Sets Spacewalk for Station Maintenance, Live Coverage Planned <https://www.nasa.gov/news-release/nasa-sets-spacewalk-for-station-maintenance-live-coverage-planned>
- [science] keep=0.9197 fit=2.2621 kind=news | step1 ai_for_science nw=4 fit=4 | Volunteer Develops Machine-Learning Tool to Identify Rare Clouds <https://science.nasa.gov/get-involved/citizen-science/volunteer-develops-machine-learning-tool-to-identify-rare-clouds>
