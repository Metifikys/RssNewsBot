# Jev pre-filter benchmark — 1500 answered items
total cost $0.0000, latency p50 99ms p90 127ms, avg input tokens 1462

## Separation (AUC: covered above rejected; 0.5 = coin flip)
| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |
|---|---|---|---|---|
| gaming | 250 / 250 | 0.515 | 0.582 | 0.998 |
| tech | 250 / 250 | 0.580 | 0.616 | 0.999 |
| science | 250 / 250 | 0.559 | 0.634 | 1.000 |
| ALL | 750 / 750 | 0.551 | 0.604 | 0.998 |

### keep — drop when keep < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.05 | 75.2% (188/250) | 72.4% (181/250) | 50.9% |
| gaming | 0.1 | 84.4% (211/250) | 82.4% (206/250) | 50.6% |
| gaming | 0.2 | 90.0% (225/250) | 93.6% (234/250) | 49.0% |
| gaming | 0.3 | 92.4% (231/250) | 95.6% (239/250) | 49.1% |
| gaming | 0.5 | 98.4% (246/250) | 99.6% (249/250) | 49.7% |
| tech | 0.05 | 88.0% (220/250) | 79.6% (199/250) | 52.5% |
| tech | 0.1 | 91.6% (229/250) | 88.8% (222/250) | 50.8% |
| tech | 0.2 | 98.0% (245/250) | 94.8% (237/250) | 50.8% |
| tech | 0.3 | 99.2% (248/250) | 97.6% (244/250) | 50.4% |
| tech | 0.5 | 99.6% (249/250) | 100.0% (250/250) | 49.9% |
| science | 0.05 | 89.2% (223/250) | 86.4% (216/250) | 50.8% |
| science | 0.1 | 95.2% (238/250) | 93.2% (233/250) | 50.5% |
| science | 0.2 | 98.4% (246/250) | 99.2% (248/250) | 49.8% |
| science | 0.3 | 100.0% (250/250) | 100.0% (250/250) | 50.0% |
| science | 0.5 | 100.0% (250/250) | 100.0% (250/250) | 50.0% |

### fit — drop when fit < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.3 | 1.6% (4/250) | 0.4% (1/250) | 80.0% |
| gaming | 0.5 | 5.2% (13/250) | 2.4% (6/250) | 68.4% |
| gaming | 0.75 | 15.2% (38/250) | 7.2% (18/250) | 67.9% |
| gaming | 1 | 24.4% (61/250) | 15.6% (39/250) | 61.0% |
| tech | 0.3 | 3.2% (8/250) | 2.8% (7/250) | 53.3% |
| tech | 0.5 | 14.4% (36/250) | 4.4% (11/250) | 76.6% |
| tech | 0.75 | 24.8% (62/250) | 9.6% (24/250) | 72.1% |
| tech | 1 | 32.8% (82/250) | 17.2% (43/250) | 65.6% |
| science | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.5 | 1.2% (3/250) | 0.0% (0/250) | 100.0% |
| science | 0.75 | 4.4% (11/250) | 0.0% (0/250) | 100.0% |
| science | 1 | 6.8% (17/250) | 1.2% (3/250) | 85.0% |

### combined — drop when keep < 0.2 AND fit < 0.75
| category | junk dropped | lost news | precision |
|---|---|---|---|
| gaming | 14.4% | 7.2% | 66.7% |
| tech | 24.8% | 9.6% | 72.1% |
| science | 4.4% | 0.0% | 100.0% |

## Kind distribution (Jev) by Step 1 verdict

### gaming
| kind | rejected | covered |
|---|---|---|
| news | 61 | 116 |
| other | 96 | 81 |
| review | 52 | 51 |
| guide | 32 | 1 |
| opinion | 9 | 1 |

### tech
| kind | rejected | covered |
|---|---|---|
| news | 73 | 150 |
| other | 111 | 77 |
| review | 38 | 19 |
| opinion | 15 | 2 |
| guide | 13 | 2 |

### science
| kind | rejected | covered |
|---|---|---|
| news | 153 | 214 |
| other | 73 | 35 |
| review | 11 | 0 |
| guide | 8 | 0 |
| opinion | 5 | 1 |

## Disagreements worth eyeballing

### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops
- [gaming] keep=0.0145 fit=1.6093 kind=news | step1 release_date nw=7 fit=9 | Persona 6 Release Window Teased in Sega's Physical Release Reveal <https://www.polygon.com/persona-6-physical-release-date-window>
- [tech] keep=0.0565 fit=2.5407 kind=news | step1 chip_business nw=9 fit=9 |  Hot Chips 2026: Nvidia breaks down 88-core Vera CPU — spatial multithreading benchmarked, <https://www.tomshardware.com/pc-components/cpus/hot-chips-2026-nvidia-breaks-down-88-core-vera-cpu-spatial-multithreading-benchmarked-1-2-tb-s-socamm2-memory-agentic-workloads-detailed-and-more>
- [tech] keep=0.011 fit=1.7202 kind=other | step1 product_shutdown nw=8 fit=9 | Read-it-later app Pocket shut down down — here are the best alternatives <https://techcrunch.com/2026/08/14/read-it-later-app-pocket-is-shutting-down-here-are-the-best-alternatives>
- [tech] keep=0.0388 fit=2.6109 kind=news | step1 vulnerability nw=8 fit=9 | Splunk patches 9.1 CVSS RCE in MCP Server and 9 flaws in AI Toolkit <https://cyberupdates365.com/splunk-mcp-server-rce-patch>
- [tech] keep=0.0222 fit=1.4889 kind=other | step1 security_incident nw=8 fit=9 | Rust Supply-Chain Attack: arrayref 0.3.10 and the proc-macro1 Typosquat <https://www.stepsecurity.io/blog/arrayref-rust-crate-supply-chain-attack>
- [science] keep=0.0036 fit=2.0534 kind=news | step1 major_dataset_release nw=9 fit=9 | The brain, solved <https://www.scientificamerican.com/article/the-brain-solved>
- [science] keep=0.0336 fit=2.137 kind=news | step1 physics_discovery nw=9 fit=9 | Free-falling atoms in space put a new twist on Galileo’s gravity test <https://www.sciencenews.org/article/atoms-gravity-space-galileo-einstein>
- [science] keep=0.0018 fit=1.853 kind=news | step1 policy_decision nw=9 fit=9 | Here’s what the first mRNA flu vaccine could mean for flu season <https://www.sciencenews.org/article/first-mrna-flu-vaccine-fall-season>
- [science] keep=0.002 fit=1.7434 kind=news | step1 medical_research nw=9 fit=9 | Moderna and Merck announce ‘landmark’ trial showing mRNA therapy significantly cuts odds o <https://www.scientificamerican.com/article/moderna-and-merck-announce-landmark-trial-showing-mrna-therapy-significantly-cuts-odds-of-recurrent-skin-cancer>
- [science] keep=0.0565 fit=2.6522 kind=news | step1 space_mission nw=9 fit=9 | What’s next for NASA’s new Roman Space Telescope? <https://www.scientificamerican.com/article/whats-next-for-nasas-new-roman-space-telescope>
- [gaming] keep=0.0085 fit=1.8986 kind=other | step1 dev_quote nw=5 fit=8 |  "If people weren't playing No Man's Sky, I don't think we would want to do updates," says <https://www.gamesradar.com/games/survival/if-people-werent-playing-no-mans-sky-i-dont-think-we-would-want-to-do-updates-says-sean-murray-but-hed-probably-try-to-sneak-in-more-content-years-later-anyway>
- [gaming] keep=0.0194 fit=0.5398 kind=news | step1 dev_quote nw=6 fit=8 | Simpsons Creator Says Hit & Run Is Returning, Showrunner Says Otherwise <https://www.thegamer.com/the-simpsons-creator-hit-and-run-remake>
- [gaming] keep=0.0009 fit=0.7998 kind=news | step1 dev_quote nw=6 fit=8 |  "Arkane was about to go under" before it committed to Dishonored, former lead says: "We h <https://www.gamesradar.com/games/action-rpg/arkane-was-about-to-go-under-before-it-committed-to-dishonored-former-lead-says-we-had-4-months-of-money-left>
- [gaming] keep=0.004 fit=1.3382 kind=news | step1 dev_quote nw=6 fit=8 | The future of id Software — alumni from the legendary DOOM studio speaks on its future wit <https://www.windowscentral.com/gaming/zenimax-bethesda/the-future-of-id-software-now-under-xbox-the-legendary-doom-studio-speaks>
- [gaming] keep=0.0372 fit=1.8745 kind=news | step1 major_announcement nw=8 fit=8 |  Xbox is preparing a "family of devices" for next-gen Project Helix, according to CEO Asha <https://www.gamesradar.com/platforms/xbox/xbox-is-preparing-a-family-of-devices-for-next-gen-project-helix-according-to-ceo-asha-sharma>
- [gaming] keep=0.0134 fit=0.9745 kind=other | step1 preview_impressions nw=6 fit=8 | ‘You Don’t Get XP, But Zones Do’ — How Control Resonant Is Rewriting the Action RPG Rule B <https://www.ign.com/articles/you-dont-get-xp-but-zones-do-how-control-resonant-is-rewriting-the-action-rpg-rule-book>
- [gaming] keep=0.0329 fit=1.207 kind=other | step1 dev_quote nw=7 fit=8 |  GTA 6 doesn't feature microtransactions or AI, Rockstar says, but I doubt its take on GTA <https://www.gamesradar.com/games/grand-theft-auto/gta-6-doesnt-feature-microtransactions-or-ai-rockstar-says-but-i-doubt-its-take-on-gta-online-wont-have-shark-cards>
- [gaming] keep=0.0424 fit=0.9106 kind=other | step1 dev_quote nw=5 fit=8 |  Intergalactic: The Heretic Prophet "might need to be edited" to avoid "another Last of Us <https://www.gamesradar.com/games/adventure/intergalactic-the-heretic-prophet-might-need-to-be-edited-to-avoid-another-last-of-us-2-situation-former-naughty-dog-dev-warns>
- [gaming] keep=0.0184 fit=1.1222 kind=news | step1 industry_news nw=6 fit=8 | Marvel's Spider-Man 2 Gets Huge Boost 3 Years After Launch Thanks to Box Office Phenomenon <https://www.reddit.com/r/Games/comments/1vl29xf/marvels_spiderman_2_gets_huge_boost_3_years_after>
- [gaming] keep=0.0064 fit=2.1538 kind=other | step1 community_story nw=5 fit=8 | Deltarune Creator Addresses Fan Theories and Defends Chapter 5’s Pacing in Latest Update <https://www.polygon.com/deltarune-newsletter-chapter-5-creator>
- [gaming] keep=0.0015 fit=0.8031 kind=other | step1 preview_impressions nw=7 fit=8 | GTA 6 life simulation elements confirmed: thank god, we can turn the offensively hot Jason <https://www.rockpapershotgun.com/gta-6-life-simulation-elements-confirmed-thank-god-we-can-turn-the-offensively-hot-jason-and-lucia-into-exhausted-slobs-like-the-rest-of-us>
- [gaming] keep=0.0155 fit=1.8517 kind=news | step1 dev_quote nw=5 fit=8 |  After dropping a nerf bomb, Slay the Spire 2 devs say updates are slowing down so they ca <https://www.gamesradar.com/games/roguelike/after-dropping-a-nerf-bomb-slay-the-spire-2-devs-say-updates-are-slowing-down-so-they-can-focus-on-bigger-more-impactful-content-like-a-new-character>
- [gaming] keep=0.0077 fit=1.4151 kind=other | step1 dev_quote nw=7 fit=8 |  GTA 6's hand-crafted NPCs have over 600,000 animations compared to only 55,000 animations <https://www.gamesradar.com/games/grand-theft-auto/gta-6s-hand-crafted-npcs-have-over-600-000-animations-compared-to-only-55-000-animations-in-gta-5>
- [gaming] keep=0.0621 fit=1.8824 kind=review | step1 dev_quote nw=6 fit=8 |  Kingdom Hearts 4 lead Tetsuya Nomura plays 4D chess, potentially drops hints his canceled <https://www.gamesradar.com/games/kingdom-hearts/kingdom-hearts-4-lead-tetsuya-nomura-plays-4d-chess-potentially-drops-hints-his-canceled-final-fantasy-game-could-somehow-connect-to-the-next-rpg>
- [gaming] keep=0.011 fit=1.6824 kind=review | step1 preview_impressions nw=6 fit=8 |  Marvel's Wolverine will make sure squeamish players never have to see blood and gore: "We <https://www.gamesradar.com/games/action/marvels-wolverine-will-make-sure-squeamish-players-never-have-to-see-blood-and-gore-we-dont-want-to-surprise-anyone>
- [gaming] keep=0.0375 fit=1.792 kind=review | step1 preview_impressions nw=6 fit=8 | Wardogs 'Hits Different' and Could Be the Next Big FPS, Those Who Managed to Get Into the  <https://www.ign.com/articles/wardogs-hits-different-and-could-be-the-next-big-fps-those-who-managed-to-get-into-the-recent-private-playtest-say>
- [gaming] keep=0.0058 fit=1.6521 kind=other | step1 dev_quote nw=6 fit=8 | Arkane veterans Harvey Smith and Ben Horne reunite: “We’re back to the games we love” <https://www.thegamebusiness.com/p/arkane-veterans-harvey-smith-and>
- [gaming] keep=0.0183 fit=2.3454 kind=news | step1 community_story nw=6 fit=8 | Grand Theft Auto Players Fundraise $50k for Sick Fan Trying to Make It to GTA 6 <https://www.ign.com/articles/grand-theft-auto-players-fundraise-50k-for-sick-fan-trying-to-make-it-to-gta-6>
- [gaming] keep=0.0006 fit=0.4684 kind=news | step1 dev_quote nw=3 fit=8 |  'Wait for me, my Czech friend': Japanese dev adds Czech localization just for the one Ste <https://www.pcgamer.com/games/sports/wait-for-me-my-czech-friend-japanese-dev-adds-czech-localization-just-for-the-one-steam-user-who-wishlisted-his-game-in-the-czech-republic>
- [tech] keep=0.0085 fit=0.5014 kind=other | step1 ai_product_launch nw=7 fit=8 | I tried the new ChatGPT Desktop App for Linux <https://www.reddit.com/r/technology/comments/1vnqrdu/i_tried_the_new_chatgpt_desktop_app_for_linux>
- [tech] keep=0.0175 fit=1.8805 kind=news | step1 chip_business nw=8 fit=8 | World's biggest chipmaker TSMC's sales surge 45% amid buoyant AI demand <https://www.reddit.com/r/technology/comments/1vkhdo8/worlds_biggest_chipmaker_tsmcs_sales_surge_45>
- [tech] keep=0.0037 fit=1.0049 kind=news | step1 security_incident nw=7 fit=8 | DEF CON crowd suspected in fake-hotspot attack on Delta flight <https://arstechnica.com/information-technology/2026/08/def-con-crowd-suspected-in-fake-hotspot-attack-on-delta-flight>
- [tech] keep=0.0163 fit=1.2707 kind=other | step1 vulnerability nw=8 fit=8 | llama.cpp misconfiguration awareness post (RCE with --tools or -ag) <https://www.reddit.com/r/LocalLLaMA/comments/1vg7yku/llamacpp_misconfiguration_awareness_post_rce_with>
- [tech] keep=0.0003 fit=0.2741 kind=news | step1 outage nw=9 fit=8 | Оновлення Motorola спричинило семигодинний збій служби 911 у Нью-Йорку: виклики надходили  <https://mezha.ua/news/motorola-update-caused-nyc-911-outage-314695>
- [tech] keep=0.0245 fit=2.0214 kind=other | step1 ai_model_release nw=8 fit=8 | inclusionAI/Ling-3.0-flash weights are up on Hugging Face — MIT, BF16 plus an official FP8 <https://www.reddit.com/r/LocalLLaMA/comments/1vfdeek/inclusionailing30flash_weights_are_up_on_hugging>
- [tech] keep=0.0064 fit=1.0788 kind=news | step1 chip_business nw=8 fit=8 | CXMT Surpasses 90% DDR5 Yield, Challenges Industry Giants <https://www.reddit.com/r/hardware/comments/1vmu6fp/cxmt_surpasses_90_ddr5_yield_challenges_industry>
- [tech] keep=0.0026 fit=1.7449 kind=news | step1 datacenter_infrastructure nw=8 fit=8 |  Amazon secretly circumvents community vote for massive AI data center using 45-year-old r <https://www.tomshardware.com/tech-industry/data-centers/amazon-secretly-circumvents-community-vote-for-massive-ai-data-center-45-year-old-rules-lock-gilroy-residents-out-of-public-comment-window>
- [tech] keep=0.0282 fit=1.9852 kind=news | step1 chip_business nw=8 fit=8 |  Nvidia reportedly warns biggest customers of 15% price hikes on AI servers — memory costs <https://www.tomshardware.com/pc-components/dram/nvidia-reportedly-warns-biggest-customers-of-15-percent-price-hikes-on-ai-servers>
- [tech] keep=0.0111 fit=1.6513 kind=other | step1 security_research nw=7 fit=8 | Researcher tricks Apple’s Find My into sharing location data with Linux <https://www.theregister.com/security/2026/08/20/researcher-tricks-apples-find-my-into-sharing-location-data-with-linux/5290496>
- [tech] keep=0.0006 fit=0.8534 kind=news | step1 platform_update nw=7 fit=8 | YouTube запускає економ-підписку Premium Lite в усіх регіонах, де доступний Premium <https://mezha.ua/news/youtube-premium-lite-expands-to-all-premium-region-314062>

### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)
