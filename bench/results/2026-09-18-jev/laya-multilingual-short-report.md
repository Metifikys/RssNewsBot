# Jev pre-filter benchmark — 1500 answered items
total cost $0.0000, latency p50 15ms p90 20ms, avg input tokens 616

## Separation (AUC: covered above rejected; 0.5 = coin flip)
| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |
|---|---|---|---|---|
| gaming | 250 / 250 | 0.515 | 0.532 | 0.998 |
| tech | 250 / 250 | 0.603 | 0.575 | 0.999 |
| science | 250 / 250 | 0.545 | 0.556 | 1.000 |
| ALL | 750 / 750 | 0.553 | 0.552 | 0.998 |

### keep — drop when keep < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.05 | 25.6% (64/250) | 23.6% (59/250) | 52.0% |
| gaming | 0.1 | 40.4% (101/250) | 37.6% (94/250) | 51.8% |
| gaming | 0.2 | 51.2% (128/250) | 49.2% (123/250) | 51.0% |
| gaming | 0.3 | 58.4% (146/250) | 58.0% (145/250) | 50.2% |
| gaming | 0.5 | 66.4% (166/250) | 67.6% (169/250) | 49.6% |
| tech | 0.05 | 26.4% (66/250) | 18.8% (47/250) | 58.4% |
| tech | 0.1 | 34.8% (87/250) | 26.8% (67/250) | 56.5% |
| tech | 0.2 | 51.2% (128/250) | 36.4% (91/250) | 58.4% |
| tech | 0.3 | 55.2% (138/250) | 42.0% (105/250) | 56.8% |
| tech | 0.5 | 68.0% (170/250) | 50.8% (127/250) | 57.2% |
| science | 0.05 | 40.4% (101/250) | 29.6% (74/250) | 57.7% |
| science | 0.1 | 48.8% (122/250) | 39.2% (98/250) | 55.5% |
| science | 0.2 | 57.6% (144/250) | 53.2% (133/250) | 52.0% |
| science | 0.3 | 62.4% (156/250) | 58.4% (146/250) | 51.7% |
| science | 0.5 | 69.2% (173/250) | 66.8% (167/250) | 50.9% |

### fit — drop when fit < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.5 | 0.8% (2/250) | 0.8% (2/250) | 50.0% |
| gaming | 0.75 | 2.8% (7/250) | 3.6% (9/250) | 43.8% |
| gaming | 1 | 12.8% (32/250) | 9.6% (24/250) | 57.1% |
| tech | 0.3 | 0.8% (2/250) | 0.4% (1/250) | 66.7% |
| tech | 0.5 | 2.0% (5/250) | 0.8% (2/250) | 71.4% |
| tech | 0.75 | 6.8% (17/250) | 2.4% (6/250) | 73.9% |
| tech | 1 | 14.4% (36/250) | 7.2% (18/250) | 66.7% |
| science | 0.3 | 0.4% (1/250) | 0.0% (0/250) | 100.0% |
| science | 0.5 | 0.4% (1/250) | 0.0% (0/250) | 100.0% |
| science | 0.75 | 1.6% (4/250) | 0.0% (0/250) | 100.0% |
| science | 1 | 2.4% (6/250) | 0.8% (2/250) | 75.0% |

### combined — drop when keep < 0.2 AND fit < 0.75
| category | junk dropped | lost news | precision |
|---|---|---|---|
| gaming | 2.0% | 2.0% | 50.0% |
| tech | 6.4% | 1.2% | 84.2% |
| science | 1.6% | 0.0% | 100.0% |

## Kind distribution (Jev) by Step 1 verdict

### gaming
| kind | rejected | covered |
|---|---|---|
| other | 87 | 80 |
| listicle | 63 | 43 |
| review | 42 | 53 |
| news | 20 | 59 |
| deal | 13 | 12 |
| guide | 21 | 2 |
| opinion | 4 | 1 |

### tech
| kind | rejected | covered |
|---|---|---|
| other | 97 | 90 |
| news | 35 | 71 |
| listicle | 32 | 38 |
| review | 28 | 15 |
| deal | 13 | 20 |
| guide | 23 | 8 |
| opinion | 22 | 8 |

### science
| kind | rejected | covered |
|---|---|---|
| news | 76 | 120 |
| other | 77 | 86 |
| opinion | 23 | 19 |
| listicle | 29 | 6 |
| review | 21 | 10 |
| guide | 13 | 4 |
| deal | 11 | 5 |

## Disagreements worth eyeballing

### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops
- [tech] keep=0.0152 fit=1.515 kind=other | step1 product_shutdown nw=8 fit=9 | Read-it-later app Pocket shut down down — here are the best alternatives <https://techcrunch.com/2026/08/14/read-it-later-app-pocket-is-shutting-down-here-are-the-best-alternatives>
- [science] keep=0.0491 fit=2.0157 kind=news | step1 major_dataset_release nw=9 fit=9 | The brain, solved <https://www.scientificamerican.com/article/the-brain-solved>
- [science] keep=0.0003 fit=1.6721 kind=news | step1 physics_discovery nw=9 fit=9 | Free-falling atoms in space put a new twist on Galileo’s gravity test <https://www.sciencenews.org/article/atoms-gravity-space-galileo-einstein>
- [science] keep=0 fit=1.7681 kind=news | step1 astronomy_discovery nw=9 fit=9 | Extraordinarily rare supernova death spotted 500 million light-years from Earth <https://www.popsci.com/science/rare-supernova-death>
- [gaming] keep=0.0116 fit=0.6686 kind=other | step1 dev_quote nw=6 fit=8 | Simpsons Creator Says Hit & Run Is Returning, Showrunner Says Otherwise <https://www.thegamer.com/the-simpsons-creator-hit-and-run-remake>
- [gaming] keep=0.0443 fit=0.9404 kind=other | step1 dev_quote nw=6 fit=8 |  "Arkane was about to go under" before it committed to Dishonored, former lead says: "We h <https://www.gamesradar.com/games/action-rpg/arkane-was-about-to-go-under-before-it-committed-to-dishonored-former-lead-says-we-had-4-months-of-money-left>
- [gaming] keep=0.0496 fit=1.4136 kind=other | step1 dev_quote nw=7 fit=8 |  GTA 6 doesn't feature microtransactions or AI, Rockstar says, but I doubt its take on GTA <https://www.gamesradar.com/games/grand-theft-auto/gta-6-doesnt-feature-microtransactions-or-ai-rockstar-says-but-i-doubt-its-take-on-gta-online-wont-have-shark-cards>
- [gaming] keep=0.0888 fit=1.2979 kind=deal | step1 dev_quote nw=5 fit=8 |  Intergalactic: The Heretic Prophet "might need to be edited" to avoid "another Last of Us <https://www.gamesradar.com/games/adventure/intergalactic-the-heretic-prophet-might-need-to-be-edited-to-avoid-another-last-of-us-2-situation-former-naughty-dog-dev-warns>
- [gaming] keep=0.016 fit=1.273 kind=deal | step1 platform_policy nw=6 fit=8 | Baldur's Gate 3 for Xbox is now a Play Anywhere release supporting Xbox Series and Xbox on <https://www.rpgsite.net/news/21032-baldurs-gate-3-xbox-play-anywhere-release-date-discount-today>
- [gaming] keep=0.0724 fit=1.7109 kind=other | step1 dev_quote nw=5 fit=8 |  After dropping a nerf bomb, Slay the Spire 2 devs say updates are slowing down so they ca <https://www.gamesradar.com/games/roguelike/after-dropping-a-nerf-bomb-slay-the-spire-2-devs-say-updates-are-slowing-down-so-they-can-focus-on-bigger-more-impactful-content-like-a-new-character>
- [gaming] keep=0.0632 fit=0.9877 kind=news | step1 major_announcement nw=7 fit=8 | Everything Revealed At Deep Dive Into Kingdom Hearts D23 Panel <https://www.thegamer.com/kingdom-hearts-d23-2026-deep-dive-panel-kh4-all-announcements>
- [gaming] keep=0.031 fit=1.1909 kind=other | step1 dev_quote nw=7 fit=8 |  How long is GTA 6? About 80 hours, according to one Rockstar dev's playthrough  <https://www.gamesradar.com/games/grand-theft-auto/how-long-is-gta-6-about-80-hours-according-to-one-rockstar-devs-playthrough>
- [gaming] keep=0.0075 fit=1.201 kind=review | step1 preview_impressions nw=6 fit=8 |  Marvel's Wolverine will make sure squeamish players never have to see blood and gore: "We <https://www.gamesradar.com/games/action/marvels-wolverine-will-make-sure-squeamish-players-never-have-to-see-blood-and-gore-we-dont-want-to-surprise-anyone>
- [gaming] keep=0.0635 fit=1.6733 kind=other | step1 dev_quote nw=3 fit=8 |  'Wait for me, my Czech friend': Japanese dev adds Czech localization just for the one Ste <https://www.pcgamer.com/games/sports/wait-for-me-my-czech-friend-japanese-dev-adds-czech-localization-just-for-the-one-steam-user-who-wishlisted-his-game-in-the-czech-republic>
- [tech] keep=0.0873 fit=0.8511 kind=listicle | step1 rumor nw=8 fit=8 | Anthropic in Talks to Buy World Model AI Startup Decart for $6B <https://www.bloomberg.com/news/articles/2026-08-13/anthropic-said-in-talks-to-buy-ai-startup-decart-for-6-billion>
- [tech] keep=0.0698 fit=1.1106 kind=review | step1 chip_business nw=8 fit=8 | CXMT Surpasses 90% DDR5 Yield, Challenges Industry Giants <https://www.reddit.com/r/hardware/comments/1vmu6fp/cxmt_surpasses_90_ddr5_yield_challenges_industry>
- [tech] keep=0.031 fit=1.757 kind=other | step1 developer_platform_change nw=7 fit=8 | Django is moving to an annual release cycle <https://www.djangoproject.com/weblog/2026/aug/10/annual-release-cycle>
- [tech] keep=0.0346 fit=0.916 kind=other | step1 company_strategy nw=8 fit=8 | Demis Hassabis quits as Google DeepMind CEO for AGI role <https://www.reddit.com/r/technology/comments/1vit75f/demis_hassabis_quits_as_google_deepmind_ceo_for>
- [tech] keep=0.0737 fit=1.9343 kind=news | step1 ai_platform_update nw=8 fit=8 | Google plans to kill Assistant on your phone on September 4 <https://arstechnica.com/ai/2026/08/google-plans-to-kill-assistant-on-your-phone-on-september-4>
- [tech] keep=0.0286 fit=0.966 kind=deal | step1 space_infrastructure nw=9 fit=8 | $100 млрд та понад 3000 робочих: SpaceX будує найбільший у світі космодром <https://itc.ua/ua/novini/100-mlrd-ta-ponad-3000-robochyh-spacex-buduye-najbilshyj-u-sviti-kosmodrom>
- [tech] keep=0.0149 fit=0.7694 kind=listicle | step1 chip_business nw=8 fit=8 | DRAM Supply to Remain Tight in 2027, Prompting NVIDIA to Lower HBM Configurations for Rubi <https://www.reddit.com/r/hardware/comments/1vf7u82/dram_supply_to_remain_tight_in_2027_prompting>
- [tech] keep=0.0358 fit=1.9008 kind=other | step1 cloud_infrastructure nw=8 fit=8 | Cloudflare Workers and Containers now support inbound TCP connections and gRPC <https://blog.cloudflare.com/grpc-workers>
- [tech] keep=0.024 fit=1.9453 kind=deal | step1 vulnerability nw=8 fit=8 | My Homelab Got Hacked - A Postmortem <https://phunky.cafe/my-homelab-got-hacked>
- [tech] keep=0.0796 fit=1.8567 kind=news | step1 ai_model_release nw=8 fit=8 | IBM's new Granite 4.2 models ride the wave of interest in local LLMs <https://arstechnica.com/ai/2026/08/ibms-new-granite-4-2-models-ride-the-wave-of-interest-in-local-llms>
- [tech] keep=0.0055 fit=0.6851 kind=other | step1 ai_model_release nw=8 fit=8 | Qweb 3.8-Max Released - #2 in Vision Arena <https://www.reddit.com/r/LocalLLaMA/comments/1ve91jd/qweb_38max_released_2_in_vision_arena>
- [tech] keep=0.0815 fit=0.8895 kind=other | step1 court_ruling nw=8 fit=8 | Meta, other tech companies must face thousands of lawsuits over social media addiction, co <https://www.reddit.com/r/technology/comments/1vkv2wm/meta_other_tech_companies_must_face_thousands_of>
- [tech] keep=0.0083 fit=1.0983 kind=listicle | step1 layoff nw=8 fit=8 | Etsy lays off 12% of workforce as part of restructuring plan <https://www.reddit.com/r/technology/comments/1vgk0o4/etsy_lays_off_12_of_workforce_as_part_of>
- [tech] keep=0.074 fit=1.4247 kind=deal | step1 investment nw=8 fit=8 |  Micron commits $10 billion to new US-based Research Labs — Boise hub to target post-DRAM  <https://www.tomshardware.com/tech-industry/micron-commits-usd10-billion-to-new-us-based-research-labs-boise-hub-to-target-post-dram-and-nand-technologies-and-packaging>
- [tech] keep=0.0386 fit=1.6535 kind=news | step1 devtool_release nw=6 fit=8 | Numba in the Browser: Unlocking a New Scientific Python Stack in JupyterLite <https://notebook.link/blog/numba-in-the-browser>
- [tech] keep=0.0599 fit=1.8262 kind=news | step1 security_incident nw=7 fit=8 |  Bluesky reveals recent outage was caused by major DDoS attack  <https://www.techradar.com/pro/security/bluesky-reveals-recent-outage-was-caused-by-major-ddos-attack>
- [science] keep=0.0882 fit=1.9278 kind=opinion | step1 medical_research nw=8 fit=8 | Is there a ‘window of opportunity’ to prevent Alzheimer’s in women? <https://www.scientificamerican.com/article/is-there-a-window-of-opportunity-to-prevent-alzheimers-in-women>
- [science] keep=0.0471 fit=2.0024 kind=other | step1 climate_research nw=8 fit=8 | Increasing CO<sub>2</sub> levels fertilize C<sub>4</sub> grass production <https://www.nature.com/articles/s41586-026-10935-4>
- [science] keep=0.0303 fit=1.7736 kind=news | step1 biology_discovery nw=5 fit=8 | ‘Cheating’ birds change their tune <https://www.sciencenews.org/article/cheating-birds-song-pied-flycatchers>
- [science] keep=0.0962 fit=1.7727 kind=other | step1 physics_discovery nw=8 fit=8 | Neutrinos From Deep Inside Earth Provide a New Picture of the Mantle <https://www.quantamagazine.org/neutrinos-from-deep-inside-earth-provide-a-new-picture-of-the-mantle-20260807>
- [science] keep=0.0156 fit=1.8947 kind=other | step1 archaeology_paleontology nw=7 fit=8 | Ancient South American families built massive waterways by themselves <https://www.popsci.com/environment/ancient-south-america-raised-waterways>
- [science] keep=0.0002 fit=1.9321 kind=news | step1 astronomy_discovery nw=7 fit=8 | Hidden volcanoes hint at Moon’s turbulent past <https://www.nature.com/articles/d41586-026-02555-9>
- [science] keep=0.0081 fit=1.881 kind=news | step1 biology_discovery nw=7 fit=8 | Cougars Lower the Risks of Car Crashes by Hunting Deer <https://www.wired.com/story/cougars-lower-risks-car-crashes-by-hunting-deer>
- [science] keep=0.0236 fit=1.663 kind=news | step1 medical_research nw=8 fit=8 | A Mutation Is Making It Easier for Drug-Resistant Malaria to Spread <https://www.wired.com/story/genetic-mutation-drug-resistant-malaria-spread>
- [science] keep=0.0012 fit=1.7814 kind=news | step1 space_mission nw=8 fit=8 | NASA’s Roman Space Telescope could find alien Earths—but not like you think  <https://www.scientificamerican.com/article/nasas-roman-space-telescope-could-find-alien-earths-but-not-like-you-think>
- [science] keep=0.0254 fit=1.8514 kind=other | step1 medical_research nw=8 fit=8 | <i>FNIP1</i> variants are associated with favourable metabolism in 1 million humans <https://www.nature.com/articles/s41586-026-10864-2>

### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)
- [gaming] keep=0.9206 fit=2.027 kind=listicle | step1 industry_news nw=2 fit=2 | Blizzard announces a long list of BlizzCon luminaries including T-Pain, YOASOBI, and Simu  <https://massivelyop.com/2026/08/20/blizzard-announces-a-long-list-of-blizzcon-luminaries-including-t-pain-yoasobi-and-simu-liu>
- [gaming] keep=0.9167 fit=1.4995 kind=deal | step1 guide nw=1 fit=1 | Heavyweight Fishing Codes (August 2026) <https://insider-gaming.com/roblox-heavyweight-fishing-codes>
- [gaming] keep=0.9466 fit=1.1664 kind=guide | step1 community_story nw=1 fit=1 | Is Everything in Marvel REALLY Canon? <https://www.polygon.com/video/is-everything-in-marvel-really-canon>
- [gaming] keep=0.9517 fit=1.8887 kind=listicle | step1 opinion nw=1 fit=1 | Doctor Who Should Leave David Tennant Alone As He Isn't Going to Save the Franchise <https://www.polygon.com/doctor-who-reboot-david-tennant>
- [gaming] keep=0.952 fit=1.6274 kind=listicle | step1 opinion nw=3 fit=1 | Nintendo’s Forgotten ‘90s Mario Spinoff Deserves a Switch 2 Reboot <https://www.polygon.com/super-nintendo-35th-anniversary-mario-paint-switch-2>
- [gaming] keep=0.9979 fit=1.527 kind=listicle | step1 guide nw=2 fit=2 |  The 5 top games you need to play in September 2026  <https://www.gamesradar.com/games/games-releasing-september-2026>
- [gaming] keep=0.9233 fit=1.4754 kind=listicle | step1 guide nw=1 fit=1 | My Dino Life Codes (August 2026) <https://insider-gaming.com/roblox-my-dino-life-codes>
- [gaming] keep=0.9219 fit=1.8762 kind=review | step1 guide nw=2 fit=1 | How to Get The Peril Peak Mali Outfit in Fortnite Chapter 7 Season 4 <https://insider-gaming.com/fortnite-peril-peak-mali-outfit>
- [gaming] keep=0.925 fit=1.6988 kind=news | step1 community_story nw=3 fit=3 | GTA 6 Fans Think They Found Jason and Lucia's Vice City Apartment in Real Life <https://www.ign.com/articles/gta-6-fans-think-they-found-jason-and-lucias-vice-city-apartment-in-real-life>
- [gaming] keep=0.9669 fit=1.4656 kind=listicle | step1 industry_news nw=3 fit=1 | DC Officially Unveils New Superman Replacement <https://www.polygon.com/new-dcu-superman-replacement>
- [gaming] keep=0.9787 fit=1.8053 kind=review | step1 review nw=1 fit=0 | Apple's Near-Perfect Sci-Fi Series Gets Even More Ambitious (and More Heartbreaking) in Se <https://www.polygon.com/dark-matter-season-2-review>
- [gaming] keep=0.9478 fit=1.8257 kind=listicle | step1 guide nw=2 fit=1 | How to Unlock All Wrixel Skin Styles in Fortnite Chapter 7 Season 4 <https://insider-gaming.com/fortnite-wrixel-unlock-all-styles>
- [gaming] keep=0.9832 fit=1.4269 kind=listicle | step1 industry_news nw=3 fit=2 |  Zach Cregger's Resident Evil is tracking to open far bigger than any previous Resident Ev <https://www.gamesradar.com/entertainment/horror-movies/zach-creggers-resident-evil-is-tracking-to-open-far-bigger-than-any-previous-resident-evil-movie>
- [gaming] keep=0.9761 fit=2.0581 kind=news | step1 indie_demo_self_promo nw=1 fit=1 | Pinata Smash: Incremental Circus - Punk Func Games - An Incremental game to Torture Pinata <https://www.reddit.com/r/Games/comments/1vveulu/pinata_smash_incremental_circus_punk_func_games>
- [gaming] keep=0.9868 fit=2.1758 kind=opinion | step1 opinion nw=4 fit=1 | The opportunity cost of Sony's live service pivot is enormous | Opinion <https://www.gamesindustry.biz/the-opportunity-cost-of-sonys-live-service-pivot-is-enormous-opinion>
- [gaming] keep=0.966 fit=1.4874 kind=listicle | step1 opinion nw=2 fit=1 | Why horses are so hard for video games to get right <https://www.polygon.com/video/why-horses-are-so-hard-for-video-games-to-get-right>
- [gaming] keep=0.9297 fit=1.709 kind=review | step1 preview_impressions nw=3 fit=3 | Stupid Never Dies ‘Wyvern Boss Battle’ gameplay <https://www.gematsu.com/2026/08/stupid-never-dies-wyvern-boss-battle-gameplay>
- [gaming] keep=0.9604 fit=1.9794 kind=review | step1 editorial nw=2 fit=1 | Best Video Games From 2001 That Changed Their Genres <https://www.thegamer.com/best-games-2001-sixth-console-generation-changed-genre>
- [gaming] keep=0.95 fit=1.6018 kind=listicle | step1 patch nw=3 fit=2 | Marvel Rivals Upgrades Storm’s Swimsuit Cosmetic Following Fan Backlash <https://insider-gaming.com/marvel-rivals-upgrades-storms-swimsuit-cosmetic-following-fan-backlash>
- [gaming] keep=0.9631 fit=1.9041 kind=other | step1 guide nw=3 fit=1 | How does the Explorer Gadget work in Pokémon Go? <https://www.polygon.com/pokemon-go-explorer-gadget-how-to-use>
- [gaming] keep=0.937 fit=1.414 kind=listicle | step1 movie_news nw=1 fit=1 | 10 Years Later, Anne Hathaway's 110-Minute Sci-Fi Monster Movie Is Aging Like Fine Wine <https://www.polygon.com/anne-hathaway-colossal-movie-streaming>
- [gaming] keep=0.9953 fit=1.8655 kind=listicle | step1 review nw=2 fit=1 |  The highly customisable Corsair Frame 4000X promised it would be my dream PC case, but I' <https://www.pcgamer.com/hardware/pc-cases/the-highly-customisable-corsair-frame-4000x-promised-it-would-be-my-dream-pc-case-but-im-not-completely-convinced>
- [gaming] keep=0.905 fit=1.5989 kind=other | step1 industry_news nw=3 fit=2 | HBO Max's R-Rated 116-Minute Action Sci-Fi Sequel Is Officially a Streaming Smash Hit <https://www.polygon.com/mortal-kombat-2-karl-urban-streaming-success-mk3-hbo>
- [gaming] keep=0.9837 fit=1.7913 kind=listicle | step1 major_announcement nw=2 fit=2 | Woodland Rebels demo will release in 2 days! <https://www.youtube.com/watch?v=63Ol5QhKzM8>
- [gaming] keep=0.9076 fit=1.6778 kind=review | step1 guide nw=4 fit=2 | Sony PlayStation Lawsuit Refund: How To Check Eligibility and Claim Your Money <https://insider-gaming.com/sony-playstation-lawsuit-refund-check-and-claim>
