# Jev pre-filter benchmark — 1500 answered items
total cost $0.0000, latency p50 128ms p90 158ms, avg input tokens 731

## Separation (AUC: covered above rejected; 0.5 = coin flip)
| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |
|---|---|---|---|---|
| gaming | 250 / 250 | 0.498 | 0.541 | 0.998 |
| tech | 250 / 250 | 0.600 | 0.592 | 0.999 |
| science | 250 / 250 | 0.646 | 0.699 | 1.000 |
| ALL | 750 / 750 | 0.572 | 0.601 | 0.998 |

### keep — drop when keep < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.05 | 60.4% (151/250) | 60.4% (151/250) | 50.0% |
| gaming | 0.1 | 72.0% (180/250) | 70.4% (176/250) | 50.6% |
| gaming | 0.2 | 80.0% (200/250) | 79.6% (199/250) | 50.1% |
| gaming | 0.3 | 84.4% (211/250) | 84.8% (212/250) | 49.9% |
| gaming | 0.5 | 88.4% (221/250) | 91.2% (228/250) | 49.2% |
| tech | 0.05 | 89.2% (223/250) | 76.4% (191/250) | 53.9% |
| tech | 0.1 | 92.8% (232/250) | 82.4% (206/250) | 53.0% |
| tech | 0.2 | 95.2% (238/250) | 86.0% (215/250) | 52.5% |
| tech | 0.3 | 96.8% (242/250) | 92.4% (231/250) | 51.2% |
| tech | 0.5 | 98.4% (246/250) | 94.4% (236/250) | 51.0% |
| science | 0.05 | 97.2% (243/250) | 96.0% (240/250) | 50.3% |
| science | 0.1 | 98.4% (246/250) | 97.2% (243/250) | 50.3% |
| science | 0.2 | 99.6% (249/250) | 99.6% (249/250) | 50.0% |
| science | 0.3 | 100.0% (250/250) | 100.0% (250/250) | 50.0% |
| science | 0.5 | 100.0% (250/250) | 100.0% (250/250) | 50.0% |

### fit — drop when fit < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.5 | 0.4% (1/250) | 0.4% (1/250) | 50.0% |
| gaming | 0.75 | 0.8% (2/250) | 0.8% (2/250) | 50.0% |
| gaming | 1 | 2.8% (7/250) | 1.2% (3/250) | 70.0% |
| tech | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.5 | 0.0% (0/250) | 0.4% (1/250) | 0.0% |
| tech | 0.75 | 2.4% (6/250) | 0.4% (1/250) | 85.7% |
| tech | 1 | 7.2% (18/250) | 2.4% (6/250) | 75.0% |
| science | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.75 | 0.8% (2/250) | 0.0% (0/250) | 100.0% |
| science | 1 | 1.2% (3/250) | 0.0% (0/250) | 100.0% |

### combined — drop when keep < 0.2 AND fit < 0.75
| category | junk dropped | lost news | precision |
|---|---|---|---|
| gaming | 0.8% | 0.8% | 50.0% |
| tech | 2.4% | 0.4% | 85.7% |
| science | 0.8% | 0.0% | 100.0% |

## Kind distribution (Jev) by Step 1 verdict

### gaming
| kind | rejected | covered |
|---|---|---|
| news | 56 | 107 |
| review | 81 | 71 |
| other | 46 | 34 |
| guide | 47 | 6 |
| opinion | 18 | 32 |
| deal | 1 | 0 |
| listicle | 1 | 0 |

### tech
| kind | rejected | covered |
|---|---|---|
| news | 64 | 132 |
| review | 69 | 42 |
| opinion | 49 | 29 |
| other | 35 | 28 |
| guide | 31 | 19 |
| listicle | 1 | 0 |
| deal | 1 | 0 |

### science
| kind | rejected | covered |
|---|---|---|
| news | 132 | 208 |
| opinion | 53 | 13 |
| other | 36 | 24 |
| review | 18 | 5 |
| guide | 10 | 0 |
| deal | 1 | 0 |

## Disagreements worth eyeballing

### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops
- [tech] keep=0.0637 fit=2.3984 kind=news | step1 chip_business nw=9 fit=9 |  Hot Chips 2026: Nvidia breaks down 88-core Vera CPU — spatial multithreading benchmarked, <https://www.tomshardware.com/pc-components/cpus/hot-chips-2026-nvidia-breaks-down-88-core-vera-cpu-spatial-multithreading-benchmarked-1-2-tb-s-socamm2-memory-agentic-workloads-detailed-and-more>
- [tech] keep=0.0062 fit=1.8289 kind=review | step1 product_shutdown nw=8 fit=9 | Read-it-later app Pocket shut down down — here are the best alternatives <https://techcrunch.com/2026/08/14/read-it-later-app-pocket-is-shutting-down-here-are-the-best-alternatives>
- [tech] keep=0.0063 fit=1.9796 kind=news | step1 ai_platform_update nw=9 fit=9 | Google’s Gemini app surges to one billion users <https://techcrunch.com/2026/08/11/googles-gemini-app-surges-to-one-billion-users>
- [tech] keep=0.0074 fit=2.1353 kind=news | step1 vulnerability nw=8 fit=9 | Splunk patches 9.1 CVSS RCE in MCP Server and 9 flaws in AI Toolkit <https://cyberupdates365.com/splunk-mcp-server-rce-patch>
- [tech] keep=0.0065 fit=2.0009 kind=news | step1 security_incident nw=8 fit=9 | Rust Supply-Chain Attack: arrayref 0.3.10 and the proc-macro1 Typosquat <https://www.stepsecurity.io/blog/arrayref-rust-crate-supply-chain-attack>
- [science] keep=0.0052 fit=2.245 kind=news | step1 major_dataset_release nw=9 fit=9 | The brain, solved <https://www.scientificamerican.com/article/the-brain-solved>
- [science] keep=0.0177 fit=2.4446 kind=news | step1 physics_discovery nw=9 fit=9 | Free-falling atoms in space put a new twist on Galileo’s gravity test <https://www.sciencenews.org/article/atoms-gravity-space-galileo-einstein>
- [science] keep=0.0073 fit=2.2305 kind=news | step1 policy_decision nw=9 fit=9 | Here’s what the first mRNA flu vaccine could mean for flu season <https://www.sciencenews.org/article/first-mrna-flu-vaccine-fall-season>
- [science] keep=0.0087 fit=2.2218 kind=news | step1 medical_research nw=9 fit=9 | Moderna and Merck announce ‘landmark’ trial showing mRNA therapy significantly cuts odds o <https://www.scientificamerican.com/article/moderna-and-merck-announce-landmark-trial-showing-mrna-therapy-significantly-cuts-odds-of-recurrent-skin-cancer>
- [science] keep=0.0071 fit=2.4532 kind=news | step1 space_mission nw=9 fit=9 | What’s next for NASA’s new Roman Space Telescope? <https://www.scientificamerican.com/article/whats-next-for-nasas-new-roman-space-telescope>
- [gaming] keep=0.0018 fit=1.8374 kind=opinion | step1 dev_quote nw=5 fit=8 |  "If people weren't playing No Man's Sky, I don't think we would want to do updates," says <https://www.gamesradar.com/games/survival/if-people-werent-playing-no-mans-sky-i-dont-think-we-would-want-to-do-updates-says-sean-murray-but-hed-probably-try-to-sneak-in-more-content-years-later-anyway>
- [gaming] keep=0.0036 fit=0.7697 kind=news | step1 dev_quote nw=6 fit=8 | Simpsons Creator Says Hit & Run Is Returning, Showrunner Says Otherwise <https://www.thegamer.com/the-simpsons-creator-hit-and-run-remake>
- [gaming] keep=0.001 fit=1.2669 kind=news | step1 dev_quote nw=6 fit=8 |  "Arkane was about to go under" before it committed to Dishonored, former lead says: "We h <https://www.gamesradar.com/games/action-rpg/arkane-was-about-to-go-under-before-it-committed-to-dishonored-former-lead-says-we-had-4-months-of-money-left>
- [gaming] keep=0.0031 fit=2.0507 kind=opinion | step1 dev_quote nw=6 fit=8 | The future of id Software — alumni from the legendary DOOM studio speaks on its future wit <https://www.windowscentral.com/gaming/zenimax-bethesda/the-future-of-id-software-now-under-xbox-the-legendary-doom-studio-speaks>
- [gaming] keep=0.0043 fit=1.8231 kind=opinion | step1 dev_quote nw=7 fit=8 |  GTA 6 doesn't feature microtransactions or AI, Rockstar says, but I doubt its take on GTA <https://www.gamesradar.com/games/grand-theft-auto/gta-6-doesnt-feature-microtransactions-or-ai-rockstar-says-but-i-doubt-its-take-on-gta-online-wont-have-shark-cards>
- [gaming] keep=0.0021 fit=1.5328 kind=review | step1 dev_quote nw=5 fit=8 |  Intergalactic: The Heretic Prophet "might need to be edited" to avoid "another Last of Us <https://www.gamesradar.com/games/adventure/intergalactic-the-heretic-prophet-might-need-to-be-edited-to-avoid-another-last-of-us-2-situation-former-naughty-dog-dev-warns>
- [gaming] keep=0.0047 fit=1.6151 kind=review | step1 industry_news nw=6 fit=8 | Marvel's Spider-Man 2 Gets Huge Boost 3 Years After Launch Thanks to Box Office Phenomenon <https://www.reddit.com/r/Games/comments/1vl29xf/marvels_spiderman_2_gets_huge_boost_3_years_after>
- [gaming] keep=0.0431 fit=2.1046 kind=other | step1 community_story nw=5 fit=8 | Deltarune Creator Addresses Fan Theories and Defends Chapter 5’s Pacing in Latest Update <https://www.polygon.com/deltarune-newsletter-chapter-5-creator>
- [gaming] keep=0.0081 fit=1.2347 kind=opinion | step1 preview_impressions nw=7 fit=8 | GTA 6 life simulation elements confirmed: thank god, we can turn the offensively hot Jason <https://www.rockpapershotgun.com/gta-6-life-simulation-elements-confirmed-thank-god-we-can-turn-the-offensively-hot-jason-and-lucia-into-exhausted-slobs-like-the-rest-of-us>
- [gaming] keep=0.0013 fit=1.8604 kind=news | step1 dev_quote nw=5 fit=8 |  After dropping a nerf bomb, Slay the Spire 2 devs say updates are slowing down so they ca <https://www.gamesradar.com/games/roguelike/after-dropping-a-nerf-bomb-slay-the-spire-2-devs-say-updates-are-slowing-down-so-they-can-focus-on-bigger-more-impactful-content-like-a-new-character>
- [gaming] keep=0.0007 fit=1.6276 kind=review | step1 dev_quote nw=7 fit=8 |  GTA 6's hand-crafted NPCs have over 600,000 animations compared to only 55,000 animations <https://www.gamesradar.com/games/grand-theft-auto/gta-6s-hand-crafted-npcs-have-over-600-000-animations-compared-to-only-55-000-animations-in-gta-5>
- [gaming] keep=0.0316 fit=1.911 kind=news | step1 major_announcement nw=7 fit=8 | Everything Revealed At Deep Dive Into Kingdom Hearts D23 Panel <https://www.thegamer.com/kingdom-hearts-d23-2026-deep-dive-panel-kh4-all-announcements>
- [gaming] keep=0.012 fit=1.9209 kind=review | step1 dev_quote nw=7 fit=8 |  How long is GTA 6? About 80 hours, according to one Rockstar dev's playthrough  <https://www.gamesradar.com/games/grand-theft-auto/how-long-is-gta-6-about-80-hours-according-to-one-rockstar-devs-playthrough>
- [gaming] keep=0.0162 fit=1.7811 kind=review | step1 dev_quote nw=6 fit=8 |  Kingdom Hearts 4 lead Tetsuya Nomura plays 4D chess, potentially drops hints his canceled <https://www.gamesradar.com/games/kingdom-hearts/kingdom-hearts-4-lead-tetsuya-nomura-plays-4d-chess-potentially-drops-hints-his-canceled-final-fantasy-game-could-somehow-connect-to-the-next-rpg>
- [gaming] keep=0.0059 fit=1.7845 kind=review | step1 preview_impressions nw=6 fit=8 |  Marvel's Wolverine will make sure squeamish players never have to see blood and gore: "We <https://www.gamesradar.com/games/action/marvels-wolverine-will-make-sure-squeamish-players-never-have-to-see-blood-and-gore-we-dont-want-to-surprise-anyone>
- [gaming] keep=0.05 fit=1.9949 kind=other | step1 dev_quote nw=6 fit=8 | Arkane veterans Harvey Smith and Ben Horne reunite: “We’re back to the games we love” <https://www.thegamebusiness.com/p/arkane-veterans-harvey-smith-and>
- [gaming] keep=0.0006 fit=1.2538 kind=opinion | step1 dev_quote nw=3 fit=8 |  'Wait for me, my Czech friend': Japanese dev adds Czech localization just for the one Ste <https://www.pcgamer.com/games/sports/wait-for-me-my-czech-friend-japanese-dev-adds-czech-localization-just-for-the-one-steam-user-who-wishlisted-his-game-in-the-czech-republic>
- [tech] keep=0.0008 fit=1.1814 kind=review | step1 ai_product_launch nw=7 fit=8 | I tried the new ChatGPT Desktop App for Linux <https://www.reddit.com/r/technology/comments/1vnqrdu/i_tried_the_new_chatgpt_desktop_app_for_linux>
- [tech] keep=0.0162 fit=1.4122 kind=news | step1 rumor nw=8 fit=8 | Anthropic in Talks to Buy World Model AI Startup Decart for $6B <https://www.bloomberg.com/news/articles/2026-08-13/anthropic-said-in-talks-to-buy-ai-startup-decart-for-6-billion>
- [tech] keep=0.0023 fit=1.9732 kind=opinion | step1 chip_business nw=8 fit=8 | World's biggest chipmaker TSMC's sales surge 45% amid buoyant AI demand <https://www.reddit.com/r/technology/comments/1vkhdo8/worlds_biggest_chipmaker_tsmcs_sales_surge_45>
- [tech] keep=0.0015 fit=1.7163 kind=news | step1 security_incident nw=7 fit=8 | DEF CON crowd suspected in fake-hotspot attack on Delta flight <https://arstechnica.com/information-technology/2026/08/def-con-crowd-suspected-in-fake-hotspot-attack-on-delta-flight>
- [tech] keep=0.0064 fit=1.9356 kind=other | step1 vulnerability nw=8 fit=8 | llama.cpp misconfiguration awareness post (RCE with --tools or -ag) <https://www.reddit.com/r/LocalLLaMA/comments/1vg7yku/llamacpp_misconfiguration_awareness_post_rce_with>
- [tech] keep=0.0012 fit=0.8634 kind=news | step1 outage nw=9 fit=8 | Оновлення Motorola спричинило семигодинний збій служби 911 у Нью-Йорку: виклики надходили  <https://mezha.ua/news/motorola-update-caused-nyc-911-outage-314695>
- [tech] keep=0.0102 fit=2.1531 kind=other | step1 ai_model_release nw=8 fit=8 | inclusionAI/Ling-3.0-flash weights are up on Hugging Face — MIT, BF16 plus an official FP8 <https://www.reddit.com/r/LocalLLaMA/comments/1vfdeek/inclusionailing30flash_weights_are_up_on_hugging>
- [tech] keep=0.0024 fit=1.7244 kind=opinion | step1 chip_business nw=8 fit=8 | CXMT Surpasses 90% DDR5 Yield, Challenges Industry Giants <https://www.reddit.com/r/hardware/comments/1vmu6fp/cxmt_surpasses_90_ddr5_yield_challenges_industry>
- [tech] keep=0.0112 fit=2.3994 kind=news | step1 developer_platform_change nw=7 fit=8 | Django is moving to an annual release cycle <https://www.djangoproject.com/weblog/2026/aug/10/annual-release-cycle>
- [tech] keep=0.0025 fit=1.9053 kind=news | step1 datacenter_infrastructure nw=8 fit=8 |  Amazon secretly circumvents community vote for massive AI data center using 45-year-old r <https://www.tomshardware.com/tech-industry/data-centers/amazon-secretly-circumvents-community-vote-for-massive-ai-data-center-45-year-old-rules-lock-gilroy-residents-out-of-public-comment-window>
- [tech] keep=0.0041 fit=2.3488 kind=news | step1 chip_business nw=8 fit=8 |  Nvidia reportedly warns biggest customers of 15% price hikes on AI servers — memory costs <https://www.tomshardware.com/pc-components/dram/nvidia-reportedly-warns-biggest-customers-of-15-percent-price-hikes-on-ai-servers>
- [tech] keep=0.002 fit=1.4562 kind=news | step1 platform_update nw=7 fit=8 | YouTube запускає економ-підписку Premium Lite в усіх регіонах, де доступний Premium <https://mezha.ua/news/youtube-premium-lite-expands-to-all-premium-region-314062>
- [tech] keep=0.004 fit=2.288 kind=guide | step1 developer_platform_change nw=8 fit=8 | Run CI/CD for millions of repos — on your platform, on Cloudflare <https://blog.cloudflare.com/ci-workflows>

### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)
- [gaming] keep=0.9635 fit=2.1686 kind=guide | step1 minor_mod_release nw=2 fit=2 | You Can Now Play Driveclub at 1440p, 4K, 8K & 60FPS, Only on PC <https://www.dsogaming.com/news/you-can-now-play-driveclub-at-1440p-4k-8k-60fps-only-on-pc>
- [gaming] keep=0.9058 fit=2.2034 kind=news | step1 indie_self_promo nw=2 fit=1 | Isles of Innsmouth - Zugalu Entertainment - Cozy Horror City Builder <https://www.reddit.com/r/Games/comments/1vpy0vt/isles_of_innsmouth_zugalu_entertainment_cozy>
- [gaming] keep=0.9867 fit=2.4373 kind=review | step1 indie_demo_self_promo nw=1 fit=1 | Pinata Smash: Incremental Circus - Punk Func Games - An Incremental game to Torture Pinata <https://www.reddit.com/r/Games/comments/1vveulu/pinata_smash_incremental_circus_punk_func_games>
- [gaming] keep=0.9577 fit=2.3108 kind=other | step1 hands_on_preview nw=3 fit=3 | NEW DLSS 4.5 Ray Reconstruction vs DLSS 4 - Graphics/Performance Comparison <https://youtu.be/Ua49BvNMDOQ>
- [gaming] keep=0.922 fit=2.4368 kind=review | step1 industry_news nw=2 fit=2 | This Week’s Japanese Game Releases: Starsand Island, STEINS;GATE RE:BOOT, more <https://www.gematsu.com/2026/08/this-weeks-japanese-game-releases-starsand-island-steinsgate-reboot-more>
- [gaming] keep=0.9757 fit=2.1348 kind=guide | step1 guide nw=1 fit=1 | Complete Blood of Dawnwalker Trophy List—How To Get All Achievements & Trophies <https://insider-gaming.com/blood-of-dawnwalker-trophies-achievements-how-to>
- [gaming] keep=0.9331 fit=2.1479 kind=other | step1 preorder_discount_deal nw=2 fit=1 | Preorder Metal Gear Solid Master Collection Vol. 2 on PC and Save 23% (Out Thursday) <https://www.ign.com/articles/metal-gear-solid-master-collection-vol-2-pc-preorder-discount>
- [tech] keep=0.9042 fit=2.2501 kind=review | step1 guide nw=1 fit=0 |  The 8 coolest screen-free fitness trackers you can buy in 2026 — from the Garmin Cirqa to <https://www.techradar.com/health-fitness/fitness-trackers/the-8-coolest-screen-free-fitness-trackers-you-can-buy-in-2026-from-the-garmin-cirqa-to-the-google-fitbit-air>
