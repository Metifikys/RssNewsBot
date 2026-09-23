# Jev pre-filter benchmark — 1500 answered items
total cost $0.0916, latency p50 371ms p90 552ms, avg input tokens 1454

## Separation (AUC: covered above rejected; 0.5 = coin flip)
| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |
|---|---|---|---|---|
| gaming | 250 / 250 | 0.895 | 0.918 | 0.998 |
| tech | 250 / 250 | 0.898 | 0.872 | 0.999 |
| science | 250 / 250 | 0.860 | 0.914 | 1.000 |
| ALL | 750 / 750 | 0.864 | 0.886 | 0.998 |

### keep — drop when keep < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.05 | 36.4% (91/250) | 0.8% (2/250) | 97.8% |
| gaming | 0.1 | 60.8% (152/250) | 4.0% (10/250) | 93.8% |
| gaming | 0.2 | 75.2% (188/250) | 11.2% (28/250) | 87.0% |
| gaming | 0.3 | 84.4% (211/250) | 21.2% (53/250) | 79.9% |
| gaming | 0.5 | 92.4% (231/250) | 44.8% (112/250) | 67.3% |
| tech | 0.05 | 32.8% (82/250) | 0.0% (0/250) | 100.0% |
| tech | 0.1 | 50.4% (126/250) | 0.8% (2/250) | 98.4% |
| tech | 0.2 | 65.2% (163/250) | 2.8% (7/250) | 95.9% |
| tech | 0.3 | 72.4% (181/250) | 7.2% (18/250) | 91.0% |
| tech | 0.5 | 88.0% (220/250) | 36.0% (90/250) | 71.0% |
| science | 0.05 | 7.6% (19/250) | 0.0% (0/250) | 100.0% |
| science | 0.1 | 20.4% (51/250) | 0.0% (0/250) | 100.0% |
| science | 0.2 | 36.8% (92/250) | 0.0% (0/250) | 100.0% |
| science | 0.3 | 46.0% (115/250) | 0.8% (2/250) | 98.3% |
| science | 0.5 | 68.4% (171/250) | 10.8% (27/250) | 86.4% |

### fit — drop when fit < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.3 | 64.8% (162/250) | 6.8% (17/250) | 90.5% |
| gaming | 0.5 | 72.8% (182/250) | 8.4% (21/250) | 89.7% |
| gaming | 0.75 | 80.8% (202/250) | 12.8% (32/250) | 86.3% |
| gaming | 1 | 84.8% (212/250) | 21.6% (54/250) | 79.7% |
| tech | 0.3 | 52.4% (131/250) | 3.2% (8/250) | 94.2% |
| tech | 0.5 | 60.8% (152/250) | 5.6% (14/250) | 91.6% |
| tech | 0.75 | 68.4% (171/250) | 12.0% (30/250) | 85.1% |
| tech | 1 | 79.2% (198/250) | 22.4% (56/250) | 78.0% |
| science | 0.3 | 32.8% (82/250) | 0.0% (0/250) | 100.0% |
| science | 0.5 | 40.0% (100/250) | 0.4% (1/250) | 99.0% |
| science | 0.75 | 48.8% (122/250) | 1.2% (3/250) | 97.6% |
| science | 1 | 64.0% (160/250) | 4.4% (11/250) | 93.6% |

### combined — drop when keep < 0.2 AND fit < 0.75
| category | junk dropped | lost news | precision |
|---|---|---|---|
| gaming | 73.6% | 10.0% | 88.0% |
| tech | 60.8% | 2.0% | 96.8% |
| science | 32.8% | 0.0% | 100.0% |

## Kind distribution (Jev) by Step 1 verdict

### gaming
| kind | rejected | covered |
|---|---|---|
| announcement | 37 | 57 |
| quote_interview | 19 | 46 |
| opinion_analysis | 40 | 12 |
| guide_howto | 44 | 2 |
| listicle_roundup | 31 | 9 |
| review | 18 | 12 |
| rumor_leak | 3 | 26 |
| community_story | 14 | 14 |
| release_date | 7 | 20 |
| update_patch | 9 | 16 |
| business | 5 | 18 |
| deal_promo | 11 | 1 |
| other | 5 | 5 |
| policy_regulation | 2 | 7 |
| entertainment_offtopic | 5 | 0 |
| security_incident | 0 | 3 |
| research_result | 0 | 2 |

### tech
| kind | rejected | covered |
|---|---|---|
| announcement | 32 | 88 |
| opinion_analysis | 63 | 3 |
| business | 14 | 45 |
| other | 26 | 15 |
| research_result | 18 | 20 |
| security_incident | 4 | 25 |
| policy_regulation | 9 | 19 |
| guide_howto | 19 | 2 |
| update_patch | 2 | 16 |
| review | 14 | 3 |
| quote_interview | 9 | 4 |
| community_story | 10 | 2 |
| listicle_roundup | 11 | 1 |
| rumor_leak | 5 | 3 |
| deal_promo | 8 | 0 |
| release_date | 3 | 4 |
| entertainment_offtopic | 3 | 0 |

### science
| kind | rejected | covered |
|---|---|---|
| research_result | 40 | 189 |
| opinion_analysis | 81 | 10 |
| announcement | 39 | 16 |
| other | 39 | 11 |
| policy_regulation | 2 | 15 |
| guide_howto | 16 | 0 |
| listicle_roundup | 9 | 3 |
| community_story | 7 | 0 |
| review | 7 | 0 |
| quote_interview | 4 | 0 |
| update_patch | 2 | 1 |
| deal_promo | 3 | 0 |
| release_date | 0 | 3 |
| business | 0 | 2 |
| entertainment_offtopic | 1 | 0 |

## Disagreements worth eyeballing

### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops
- [gaming] keep=0.06 fit=0.13 kind=quote_interview | step1 dev_quote nw=6 fit=8 | Simpsons Creator Says Hit & Run Is Returning, Showrunner Says Otherwise <https://www.thegamer.com/the-simpsons-creator-hit-and-run-remake>
- [gaming] keep=0.08 fit=0.09 kind=opinion_analysis | step1 preview_impressions nw=7 fit=8 | GTA 6 life simulation elements confirmed: thank god, we can turn the offensively hot Jason <https://www.rockpapershotgun.com/gta-6-life-simulation-elements-confirmed-thank-god-we-can-turn-the-offensively-hot-jason-and-lucia-into-exhausted-slobs-like-the-rest-of-us>
- [tech] keep=0.06 fit=0.13 kind=review | step1 ai_product_launch nw=7 fit=8 | I tried the new ChatGPT Desktop App for Linux <https://www.reddit.com/r/technology/comments/1vnqrdu/i_tried_the_new_chatgpt_desktop_app_for_linux>
- [gaming] keep=0.04 fit=0 kind=review | step1 review nw=6 fit=7 | Orbitals: The Kotaku Review <https://kotaku.com/orbitals-review-hazelight-takes-two-split-fiction-switch-2-2000730026>
- [gaming] keep=0.08 fit=0.04 kind=review | step1 preview_impressions nw=6 fit=7 | Control Resonant Made Me Feel Foolish For Ever Doubting It — IGN First <https://www.ign.com/articles/control-resonant-final-preview>
- [gaming] keep=0.09 fit=0.18 kind=community_story | step1 community_story nw=4 fit=6 | I Went Flying With Big Walk Folk Hero John Bigwalk <https://kotaku.com/i-went-flying-with-big-walk-folk-hero-john-bigwalk-2000728371>
- [gaming] keep=0.07 fit=0.06 kind=community_story | step1 community_story nw=4 fit=6 |  For an unhinged 84 minutes, Slay the Spire 2 expert contemplates 1 damage and explains a  <https://www.gamesradar.com/games/roguelike/for-an-unhinged-84-minutes-slay-the-spire-2-expert-contemplates-1-damage-and-explains-a-fight-simulator-built-to-solve-the-game>
- [gaming] keep=0.03 fit=0.07 kind=release_date | step1 cancellation nw=5 fit=6 | Fortune's Run cancelled due to developer's ex stealing all their money <https://store.steampowered.com/news/app/1692240/view/695395921862592057?l=english>
- [gaming] keep=0.08 fit=0.29 kind=opinion_analysis | step1 patch nw=5 fit=5 | I'm not saying Bethesda are trolling me specifically, but Fallout 4 and Skyrim updates are <https://www.rockpapershotgun.com/fallout-4-and-skyrim-updates-are-imminent-just-as-ive-modded-up-the-latter-for-the-first-time-in-ages>
- [gaming] keep=0.08 fit=0.1 kind=opinion_analysis | step1 industry_news nw=5 fit=5 |  We've been tracking graphics card prices for exactly one year and it's never been worse t <https://www.pcgamer.com/hardware/graphics-cards/weve-been-tracking-graphics-card-prices-for-exactly-one-year-and-its-never-been-worse-than-this>
- [tech] keep=0.05 fit=0.11 kind=release_date | step1 historical_archive nw=2 fit=5 | ‘Farewell, Etaoin Shrdlu’ <https://archive.org/details/FarewellEtaoinShrdlu>
- [gaming] keep=0.08 fit=0.22 kind=rumor_leak | step1 leak nw=5 fit=4 | They found Gambit, Daredevil, and the Fantastic Four in Tokon's files. <https://www.reddit.com/r/GamingLeaksAndRumours/comments/1vmdz1q/they_found_gambit_daredevil_and_the_fantastic>

### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)
