# Jev pre-filter benchmark — 1500 answered items
total cost $0.0000, latency p50 16ms p90 21ms, avg input tokens 1244

## Separation (AUC: covered above rejected; 0.5 = coin flip)
| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |
|---|---|---|---|---|
| gaming | 250 / 250 | 0.450 | 0.424 | 0.998 |
| tech | 250 / 250 | 0.586 | 0.519 | 0.999 |
| science | 250 / 250 | 0.448 | 0.404 | 1.000 |
| ALL | 750 / 750 | 0.489 | 0.450 | 0.998 |

### keep — drop when keep < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.05 | 5.2% (13/250) | 15.6% (39/250) | 25.0% |
| gaming | 0.1 | 13.2% (33/250) | 22.8% (57/250) | 36.7% |
| gaming | 0.2 | 25.6% (64/250) | 34.4% (86/250) | 42.7% |
| gaming | 0.3 | 33.6% (84/250) | 41.2% (103/250) | 44.9% |
| gaming | 0.5 | 46.8% (117/250) | 53.6% (134/250) | 46.6% |
| tech | 0.05 | 5.2% (13/250) | 6.4% (16/250) | 44.8% |
| tech | 0.1 | 12.4% (31/250) | 10.8% (27/250) | 53.4% |
| tech | 0.2 | 28.4% (71/250) | 18.8% (47/250) | 60.2% |
| tech | 0.3 | 38.4% (96/250) | 28.8% (72/250) | 57.1% |
| tech | 0.5 | 64.4% (161/250) | 49.2% (123/250) | 56.7% |
| science | 0.05 | 33.6% (84/250) | 45.6% (114/250) | 42.4% |
| science | 0.1 | 65.6% (164/250) | 69.6% (174/250) | 48.5% |
| science | 0.2 | 86.4% (216/250) | 88.0% (220/250) | 49.5% |
| science | 0.3 | 96.8% (242/250) | 94.4% (236/250) | 50.6% |
| science | 0.5 | 98.8% (247/250) | 98.8% (247/250) | 50.0% |

### fit — drop when fit < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.3 | 0.0% (0/250) | 0.8% (2/250) | 0.0% |
| gaming | 0.5 | 0.0% (0/250) | 1.2% (3/250) | 0.0% |
| gaming | 0.75 | 0.4% (1/250) | 2.4% (6/250) | 14.3% |
| gaming | 1 | 1.2% (3/250) | 2.8% (7/250) | 30.0% |
| tech | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.75 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 1 | 0.0% (0/250) | 0.4% (1/250) | 0.0% |
| science | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.75 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 1 | 0.0% (0/250) | 0.0% (0/250) | - |

### combined — drop when keep < 0.2 AND fit < 0.75
| category | junk dropped | lost news | precision |
|---|---|---|---|
| gaming | 0.4% | 2.0% | 16.7% |
| tech | 0.0% | 0.0% | 0.0% |
| science | 0.0% | 0.0% | 0.0% |

## Kind distribution (Jev) by Step 1 verdict

### gaming
| kind | rejected | covered |
|---|---|---|
| entertainment_offtopic | 60 | 61 |
| listicle_roundup | 38 | 36 |
| release_date | 30 | 35 |
| other | 43 | 19 |
| rumor_leak | 18 | 43 |
| security_incident | 11 | 14 |
| update_patch | 9 | 14 |
| community_story | 12 | 3 |
| quote_interview | 6 | 9 |
| announcement | 6 | 6 |
| guide_howto | 7 | 0 |
| review | 3 | 3 |
| deal_promo | 3 | 2 |
| research_result | 1 | 3 |
| opinion_analysis | 2 | 1 |
| policy_regulation | 1 | 1 |

### tech
| kind | rejected | covered |
|---|---|---|
| other | 64 | 34 |
| rumor_leak | 35 | 33 |
| listicle_roundup | 26 | 36 |
| security_incident | 21 | 28 |
| release_date | 16 | 29 |
| business | 8 | 19 |
| update_patch | 9 | 17 |
| research_result | 14 | 12 |
| entertainment_offtopic | 13 | 12 |
| announcement | 7 | 16 |
| opinion_analysis | 10 | 2 |
| community_story | 10 | 1 |
| deal_promo | 7 | 2 |
| quote_interview | 4 | 3 |
| review | 3 | 2 |
| guide_howto | 2 | 2 |
| policy_regulation | 1 | 2 |

### science
| kind | rejected | covered |
|---|---|---|
| research_result | 65 | 165 |
| other | 39 | 15 |
| release_date | 26 | 17 |
| security_incident | 16 | 17 |
| opinion_analysis | 23 | 8 |
| update_patch | 17 | 5 |
| rumor_leak | 14 | 6 |
| announcement | 11 | 3 |
| listicle_roundup | 8 | 4 |
| community_story | 10 | 2 |
| business | 3 | 6 |
| guide_howto | 6 | 0 |
| review | 4 | 0 |
| deal_promo | 4 | 0 |
| entertainment_offtopic | 3 | 0 |
| policy_regulation | 0 | 2 |
| quote_interview | 1 | 0 |

## Disagreements worth eyeballing

### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops
- [science] keep=0.0282 fit=1.2769 kind=research_result | step1 major_dataset_release nw=9 fit=9 | The brain, solved <https://www.scientificamerican.com/article/the-brain-solved>
- [science] keep=0.0338 fit=1.7044 kind=research_result | step1 physics_discovery nw=9 fit=9 | Free-falling atoms in space put a new twist on Galileo’s gravity test <https://www.sciencenews.org/article/atoms-gravity-space-galileo-einstein>
- [science] keep=0.0362 fit=1.4364 kind=research_result | step1 medical_research nw=9 fit=9 | Moderna and Merck announce ‘landmark’ trial showing mRNA therapy significantly cuts odds o <https://www.scientificamerican.com/article/moderna-and-merck-announce-landmark-trial-showing-mrna-therapy-significantly-cuts-odds-of-recurrent-skin-cancer>
- [science] keep=0.0205 fit=1.6335 kind=release_date | step1 space_mission nw=9 fit=9 | What’s next for NASA’s new Roman Space Telescope? <https://www.scientificamerican.com/article/whats-next-for-nasas-new-roman-space-telescope>
- [science] keep=0.0814 fit=1.5196 kind=research_result | step1 astronomy_discovery nw=9 fit=9 | Extraordinarily rare supernova death spotted 500 million light-years from Earth <https://www.popsci.com/science/rare-supernova-death>
- [gaming] keep=0.0547 fit=1.9848 kind=listicle_roundup | step1 dev_quote nw=5 fit=8 |  "If people weren't playing No Man's Sky, I don't think we would want to do updates," says <https://www.gamesradar.com/games/survival/if-people-werent-playing-no-mans-sky-i-dont-think-we-would-want-to-do-updates-says-sean-murray-but-hed-probably-try-to-sneak-in-more-content-years-later-anyway>
- [gaming] keep=0.013 fit=1.5176 kind=community_story | step1 dev_quote nw=6 fit=8 | Simpsons Creator Says Hit & Run Is Returning, Showrunner Says Otherwise <https://www.thegamer.com/the-simpsons-creator-hit-and-run-remake>
- [gaming] keep=0.0522 fit=1.382 kind=listicle_roundup | step1 industry_news nw=6 fit=8 | Marvel's Spider-Man 2 Gets Huge Boost 3 Years After Launch Thanks to Box Office Phenomenon <https://www.reddit.com/r/Games/comments/1vl29xf/marvels_spiderman_2_gets_huge_boost_3_years_after>
- [gaming] keep=0.0979 fit=1.5779 kind=entertainment_offtopic | step1 dev_quote nw=5 fit=8 |  After dropping a nerf bomb, Slay the Spire 2 devs say updates are slowing down so they ca <https://www.gamesradar.com/games/roguelike/after-dropping-a-nerf-bomb-slay-the-spire-2-devs-say-updates-are-slowing-down-so-they-can-focus-on-bigger-more-impactful-content-like-a-new-character>
- [gaming] keep=0.0922 fit=1.3052 kind=listicle_roundup | step1 dev_quote nw=7 fit=8 |  GTA 6's hand-crafted NPCs have over 600,000 animations compared to only 55,000 animations <https://www.gamesradar.com/games/grand-theft-auto/gta-6s-hand-crafted-npcs-have-over-600-000-animations-compared-to-only-55-000-animations-in-gta-5>
- [gaming] keep=0.0072 fit=1.5095 kind=rumor_leak | step1 major_announcement nw=7 fit=8 | Everything Revealed At Deep Dive Into Kingdom Hearts D23 Panel <https://www.thegamer.com/kingdom-hearts-d23-2026-deep-dive-panel-kh4-all-announcements>
- [gaming] keep=0.0061 fit=1.0399 kind=listicle_roundup | step1 dev_quote nw=7 fit=8 |  How long is GTA 6? About 80 hours, according to one Rockstar dev's playthrough  <https://www.gamesradar.com/games/grand-theft-auto/how-long-is-gta-6-about-80-hours-according-to-one-rockstar-devs-playthrough>
- [gaming] keep=0.0496 fit=0.916 kind=entertainment_offtopic | step1 dev_quote nw=6 fit=8 |  Kingdom Hearts 4 lead Tetsuya Nomura plays 4D chess, potentially drops hints his canceled <https://www.gamesradar.com/games/kingdom-hearts/kingdom-hearts-4-lead-tetsuya-nomura-plays-4d-chess-potentially-drops-hints-his-canceled-final-fantasy-game-could-somehow-connect-to-the-next-rpg>
- [gaming] keep=0.0806 fit=1.4887 kind=review | step1 preview_impressions nw=6 fit=8 |  Marvel's Wolverine will make sure squeamish players never have to see blood and gore: "We <https://www.gamesradar.com/games/action/marvels-wolverine-will-make-sure-squeamish-players-never-have-to-see-blood-and-gore-we-dont-want-to-surprise-anyone>
- [gaming] keep=0.0244 fit=1.3005 kind=entertainment_offtopic | step1 dev_quote nw=3 fit=8 |  'Wait for me, my Czech friend': Japanese dev adds Czech localization just for the one Ste <https://www.pcgamer.com/games/sports/wait-for-me-my-czech-friend-japanese-dev-adds-czech-localization-just-for-the-one-steam-user-who-wishlisted-his-game-in-the-czech-republic>
- [tech] keep=0.0277 fit=1.8364 kind=other | step1 chip_business nw=8 fit=8 | World's biggest chipmaker TSMC's sales surge 45% amid buoyant AI demand <https://www.reddit.com/r/technology/comments/1vkhdo8/worlds_biggest_chipmaker_tsmcs_sales_surge_45>
- [tech] keep=0.0581 fit=1.4376 kind=rumor_leak | step1 chip_business nw=8 fit=8 | CXMT Surpasses 90% DDR5 Yield, Challenges Industry Giants <https://www.reddit.com/r/hardware/comments/1vmu6fp/cxmt_surpasses_90_ddr5_yield_challenges_industry>
- [tech] keep=0.0586 fit=1.5797 kind=other | step1 chip_business nw=8 fit=8 | DRAM Supply to Remain Tight in 2027, Prompting NVIDIA to Lower HBM Configurations for Rubi <https://www.reddit.com/r/hardware/comments/1vf7u82/dram_supply_to_remain_tight_in_2027_prompting>
- [tech] keep=0.022 fit=1.9086 kind=listicle_roundup | step1 cloud_infrastructure nw=8 fit=8 | Cloudflare Workers and Containers now support inbound TCP connections and gRPC <https://blog.cloudflare.com/grpc-workers>
- [tech] keep=0.0895 fit=1.4304 kind=research_result | step1 ai_platform_update nw=8 fit=8 | Nvidia's Switchyard router reshuffles AI models mid-task to cut task costs <https://venturebeat.com/orchestration/nvidias-switchyard-router-reshuffles-ai-models-mid-task-cutting-task-costs-to-a-third-in-its-own-tests>
- [tech] keep=0.0471 fit=1.5193 kind=listicle_roundup | step1 court_ruling nw=8 fit=8 | Meta, other tech companies must face thousands of lawsuits over social media addiction, co <https://www.reddit.com/r/technology/comments/1vkv2wm/meta_other_tech_companies_must_face_thousands_of>
- [tech] keep=0.0924 fit=1.4491 kind=other | step1 ai_model_release nw=8 fit=8 | Qwen3.8-Max <https://www.reddit.com/r/LocalLLaMA/comments/1vmgp1r/qwen38max>
- [tech] keep=0.0422 fit=1.3465 kind=listicle_roundup | step1 layoff nw=8 fit=8 | Etsy lays off 12% of workforce as part of restructuring plan <https://www.reddit.com/r/technology/comments/1vgk0o4/etsy_lays_off_12_of_workforce_as_part_of>
- [tech] keep=0.0753 fit=1.8247 kind=research_result | step1 security_incident nw=8 fit=8 | China-linked LightSpy spyware caught targeting victims in 13 countries, including the US <https://techcrunch.com/2026/08/06/china-linked-lightspy-spyware-caught-targeting-victims-in-13-countries-including-the-us>
- [science] keep=0.0435 fit=1.4429 kind=research_result | step1 biology_discovery nw=8 fit=8 | Insights into longevity and virus-driven adaptation from <i>Myotis</i> bat genomes <https://www.nature.com/articles/s41586-026-10932-7>
- [science] keep=0.0193 fit=1.6651 kind=research_result | step1 engineering_breakthrough nw=6 fit=8 | Sound waves power these tiny drones <https://www.popsci.com/technology/drone-powered-by-sound-waves>
- [science] keep=0.0472 fit=1.7367 kind=update_patch | step1 engineering_breakthrough nw=7 fit=8 | Vaporization trick enables ‘tandem’ solar cells to be made at lower temperatures <https://www.nature.com/articles/d41586-026-02469-6>
- [science] keep=0.0337 fit=1.5353 kind=research_result | step1 medical_research nw=8 fit=8 | Gasdermin D-mediated delivery of caspase inhibitors to suppress pyroptosis <https://www.nature.com/articles/s41586-026-10957-y>
- [science] keep=0.0383 fit=1.61 kind=research_result | step1 medical_research nw=8 fit=8 | Is there a ‘window of opportunity’ to prevent Alzheimer’s in women? <https://www.scientificamerican.com/article/is-there-a-window-of-opportunity-to-prevent-alzheimers-in-women>
- [science] keep=0.0478 fit=1.276 kind=research_result | step1 astronomy_discovery nw=8 fit=8 | Astronomers discover the existence of a ‘black hole star’ <https://www.popsci.com/science/black-hole-star-discovery>
- [science] keep=0.0169 fit=1.3611 kind=research_result | step1 physics_discovery nw=7 fit=8 | Electric rain can eat through metal <https://www.scientificamerican.com/article/electric-rain-can-eat-through-metal>
- [science] keep=0.0093 fit=1.4243 kind=research_result | step1 physics_discovery nw=8 fit=8 | Neutrinos From Deep Inside Earth Provide a New Picture of the Mantle <https://www.quantamagazine.org/neutrinos-from-deep-inside-earth-provide-a-new-picture-of-the-mantle-20260807>
- [science] keep=0.0548 fit=1.2396 kind=research_result | step1 mathematics_discovery nw=8 fit=8 | ‘Stunning’ Percolation Proof Solves Decades-Old Puzzle About Phase Transitions <https://www.quantamagazine.org/stunning-percolation-proof-solves-decades-old-puzzle-about-phase-transitions-20260828>
- [science] keep=0.0601 fit=1.7079 kind=research_result | step1 archaeology_paleontology nw=7 fit=8 | Ancient South American families built massive waterways by themselves <https://www.popsci.com/environment/ancient-south-america-raised-waterways>
- [science] keep=0.0234 fit=1.7021 kind=research_result | step1 biology_discovery nw=7 fit=8 | Tiny patch of Amazon likely holds 40,000 insect species — many new to science <https://www.nature.com/articles/d41586-026-02350-6>
- [science] keep=0.0725 fit=1.4445 kind=release_date | step1 landing nw=9 fit=8 | China successfully lands its Zhuque-3 rocket, rivaling Blue Origin and SpaceX <https://www.scientificamerican.com/article/china-successfully-lands-its-zhuque-3-rocket-rivaling-blue-origin-and-spacex>
- [science] keep=0.0412 fit=1.5877 kind=research_result | step1 astronomy_discovery nw=7 fit=8 | Hidden volcanoes hint at Moon’s turbulent past <https://www.nature.com/articles/d41586-026-02555-9>
- [science] keep=0.0325 fit=1.5043 kind=research_result | step1 biology_discovery nw=7 fit=8 | Cougars Lower the Risks of Car Crashes by Hunting Deer <https://www.wired.com/story/cougars-lower-risks-car-crashes-by-hunting-deer>
- [science] keep=0.0304 fit=1.3507 kind=research_result | step1 medical_research nw=8 fit=8 | Scale of snakebites estimated globally <https://www.nature.com/articles/d41586-026-02642-x>
- [science] keep=0.0635 fit=1.6528 kind=announcement | step1 engineering_breakthrough nw=7 fit=8 | Space Agencies Are Trying to Keep Astronauts From Losing Their Sight <https://www.wired.com/story/space-agencies-trying-stop-astronauts-losing-sight>

### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)
- [gaming] keep=0.9702 fit=1.9811 kind=listicle_roundup | step1 opinion nw=2 fit=1 |  After over 1,000 hours in Cyberpunk 2077, I'm convinced the Nomads are the best group and <https://www.gamesradar.com/games/cyberpunk/after-over-1-000-hours-in-cyberpunk-2077-im-convinced-the-nomads-are-the-best-group-and-lifepath-in-the-rpg>
- [gaming] keep=0.9579 fit=1.6115 kind=entertainment_offtopic | step1 guide nw=3 fit=2 | All Fortnite Chapter 7 Season 4 Sprites: Effects, Rarities & More <https://insider-gaming.com/fortnite-chapter-7-season-4-sprites>
- [gaming] keep=0.9618 fit=1.8235 kind=deal_promo | step1 guide nw=1 fit=1 | Heavyweight Fishing Codes (August 2026) <https://insider-gaming.com/roblox-heavyweight-fishing-codes>
- [gaming] keep=0.9115 fit=1.8052 kind=other | step1 community_story nw=1 fit=1 | Is Everything in Marvel REALLY Canon? <https://www.polygon.com/video/is-everything-in-marvel-really-canon>
- [gaming] keep=0.9329 fit=1.841 kind=community_story | step1 listicle nw=2 fit=2 | Celebrate the SNES's 35th Birthday With the 5 Weirdest Switch Online Games <https://www.polygon.com/snes-hidden-gems-switch-online>
- [gaming] keep=0.9127 fit=1.7732 kind=rumor_leak | step1 opinion nw=1 fit=1 | Doctor Who Should Leave David Tennant Alone As He Isn't Going to Save the Franchise <https://www.polygon.com/doctor-who-reboot-david-tennant>
- [gaming] keep=0.9156 fit=1.5427 kind=release_date | step1 industry_news nw=3 fit=2 | Riot Games Announces Free-To-Attend Fan Fest for VCT EMEA Stage 2 Barcelona Finals <https://insider-gaming.com/vct-fan-fest-barcelona-stage-2-finals>
- [gaming] keep=0.9144 fit=1.6312 kind=other | step1 opinion nw=3 fit=1 | Nintendo’s Forgotten ‘90s Mario Spinoff Deserves a Switch 2 Reboot <https://www.polygon.com/super-nintendo-35th-anniversary-mario-paint-switch-2>
- [gaming] keep=0.9267 fit=1.6099 kind=other | step1 guide nw=1 fit=1 | How to Level Up & Master Crown Sprite in Fortnite Chapter 7 Season 4 <https://insider-gaming.com/fortnite-level-up-master-crown-sprite>
- [gaming] keep=0.9268 fit=1.8362 kind=other | step1 guide nw=1 fit=1 | My Dino Life Codes (August 2026) <https://insider-gaming.com/roblox-my-dino-life-codes>
- [gaming] keep=0.9394 fit=1.6443 kind=other | step1 guide nw=2 fit=1 | How to Get The Peril Peak Mali Outfit in Fortnite Chapter 7 Season 4 <https://insider-gaming.com/fortnite-peril-peak-mali-outfit>
- [gaming] keep=0.9482 fit=1.8558 kind=quote_interview | step1 industry_news nw=2 fit=2 | Violent Night 2 director reveals a disturbing scene inspired by horror master Sam Raimi <https://www.polygon.com/director-producer-violent-night-2-trailer-interview>
- [gaming] keep=0.9306 fit=1.7348 kind=other | step1 guide nw=2 fit=1 | How to Get & Use Explorer Gadget in Pokémon Go <https://insider-gaming.com/pokemon-go-explorer-gadget>
- [gaming] keep=0.9297 fit=1.9039 kind=other | step1 industry_news nw=3 fit=1 | DC Officially Unveils New Superman Replacement <https://www.polygon.com/new-dcu-superman-replacement>
- [gaming] keep=0.9023 fit=1.7387 kind=other | step1 qol_mod nw=4 fit=3 | Miracle 'Skyrim' Mod Adds Essential Feature That Was Impossible For Nearly 15 Years <https://www.polygon.com/skyrim-realistic-snow-deformation-mod-best-weather-why-impossible>
- [gaming] keep=0.9279 fit=1.857 kind=other | step1 opinion nw=3 fit=2 | Gitaroo Man Was A Weirder, Better Guitar Hero <https://kotaku.com/gitaroo-man-retro-review-guitar-hero-rhythm-games-warp-week-2002-2000724144>
- [gaming] keep=0.9317 fit=1.4566 kind=guide_howto | step1 guide nw=2 fit=1 | How to Unlock All Wrixel Skin Styles in Fortnite Chapter 7 Season 4 <https://insider-gaming.com/fortnite-wrixel-unlock-all-styles>
- [gaming] keep=0.9585 fit=2.2032 kind=entertainment_offtopic | step1 review nw=3 fit=2 |  Corsair HS35 V3 is a no-fuss, fantastic sounding wireless headset that is a must-have for <https://www.gamesradar.com/hardware/headsets-headphones/corsair-hs35-v3-wireless-review>
- [gaming] keep=0.905 fit=1.3824 kind=deal_promo | step1 guide nw=2 fit=1 | All Rewards You Can Unlock In The Battle Pass For Fortnite: Chapter 7, Season 4 <https://www.thegamer.com/fortnite-chapter-7-season-4-battle-pass-rewards-unlock>
- [gaming] keep=0.934 fit=1.6659 kind=entertainment_offtopic | step1 hands_on_preview nw=3 fit=3 | NEW DLSS 4.5 Ray Reconstruction vs DLSS 4 - Graphics/Performance Comparison <https://youtu.be/Ua49BvNMDOQ>
- [gaming] keep=0.9398 fit=1.8163 kind=other | step1 guide nw=3 fit=2 | 5 New Steam Games to Play the Weekend of August 7 <https://www.polygon.com/new-steam-games-august-7-2026>
- [gaming] keep=0.9275 fit=1.9232 kind=release_date | step1 major_announcement nw=2 fit=1 | McDonald's Happy Meals Get Hello Kitty x Godzilla Crossover Toys <https://www.polygon.com/hello-kitty-godzilla-mcdonalds-happy-meals-release-date-images>
- [gaming] keep=0.9447 fit=1.6946 kind=other | step1 guide nw=3 fit=2 | How To Opt Out of Amazon AI Training on Twitch <https://insider-gaming.com/twitch-ai-training-opt-out>
- [gaming] keep=0.9182 fit=1.7558 kind=security_incident | step1 community_story nw=1 fit=1 | Viral Gaming Toilet Chair Appears to Resurface Via Facebook Marketplace — Listed as in 'Us <https://www.ign.com/articles/viral-gaming-toilet-chair-appears-to-resurface-via-facebook-marketplace-listed-as-in-used-condition-for-500>
- [gaming] keep=0.9036 fit=1.9788 kind=listicle_roundup | step1 industry_news nw=3 fit=2 |  James Cameron says he's "literally mapped out" the last act of his career, and it might n <https://www.gamesradar.com/entertainment/sci-fi-movies/james-cameron-says-hes-literally-mapped-out-the-last-act-of-his-career-and-it-might-not-involve-directing-avatar-4-and-5-what-are-the-other-stories-i-want-to-tell>
