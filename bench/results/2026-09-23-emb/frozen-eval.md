
# tech
trained on C:/Users/user/AppData/Local/Temp/news_copy.db decisions 2026-08-06..2026-08-23: 7232 junk / 1520 kept

## sanity: old test window 2026-08-24..2026-09-03 (AUC 0.901)
| target loss | threshold | kept lost | junk dropped | Step 1 input removed |
|---|---|---|---|---|
| 0.00% | 0.0153 | 0/792 (0.00%) | 6.40% | 5.34% |
| 0.10% | 0.0379 | 1/792 (0.13%) | 16.88% | 14.11% |
| 0.25% | 0.0491 | 1/792 (0.13%) | 21.20% | 17.71% |
| 0.50% | 0.0850 | 1/792 (0.13%) | 31.31% | 26.14% |
| 1.00% | 0.1096 | 3/792 (0.38%) | 36.43% | 30.45% |
| 2.00% | 0.1737 | 9/792 (1.14%) | 48.57% | 40.71% |

## NEW records 2026-09-03..2026-09-23 from logs/news.db: 8162 junk / 1602 kept, **AUC 0.889** (no refit)
| target loss | threshold | kept lost | junk dropped | Step 1 input removed |
|---|---|---|---|---|
| 0.00% | 0.0153 | 0/1602 (0.00%) | 6.40% | 5.35% |
| 0.10% | 0.0379 | 0/1602 (0.00%) | 17.28% | 14.44% |
| 0.25% | 0.0491 | 2/1602 (0.12%) | 22.04% | 18.45% |
| 0.50% | 0.0850 | 11/1602 (0.69%) | 32.81% | 27.54% |
| 1.00% | 0.1096 | 19/1602 (1.19%) | 38.52% | 32.39% |
| 2.00% | 0.1737 | 41/1602 (2.56%) | 49.66% | 41.93% |

### per week at the chosen point (target 0.50%, threshold 0.0850)
| week from | junk / kept | AUC | kept lost | junk dropped | input removed |
|---|---|---|---|---|---|
| 2026-09-03 | 2375 / 429 | 0.891 | 1 (0.23%) | 32.17% | 27.28% |
| 2026-09-10 | 3214 / 601 | 0.895 | 6 (1.00%) | 33.45% | 28.34% |
| 2026-09-17 | 2573 / 572 | 0.879 | 4 (0.70%) | 32.61% | 26.80% |

### kept items that would be dropped at the chosen point
- 0.0452 | 2026-09-17 | step1 historical_archive nw=2 fit=5 | Style Guide for Online Hypertext (1992) <w3.org>
- 0.0483 | 2026-09-12 | step1 tech_history nw=2 fit=5 | Hand-Coded ASM Powers Homebrew SNES Game <hackaday.com>
- 0.0506 | 2026-09-14 | step1 ai_policy_regulation nw=6 fit=5 | Trump Says a Smart President Is All That’s Needed to Rein In A.I. <nytimes.com>
- 0.0563 | 2026-09-23 | step1 historical_archive nw=2 fit=5 | Re: Suggestions on implementing an efficient instruction set simulator in LuaJIT2 (2011) <web.archive.org>
- 0.0583 | 2026-09-16 | step1 historical_archive nw=2 fit=5 | Recreating Voodoo Graphics and a Late-1990s Gaming PC on an FPGA <nand2mario.github.io>
- 0.0634 | 2026-09-21 | step1 tech_history nw=2 fit=5 | CSE 452: Google's Introduction to Distributed System Design <courses.cs.washington.edu>
- 0.0661 | 2026-09-11 | step1 historical_archive nw=2 fit=5 | The Thinking Machine (1961) – AI debates 65 years ago [video] <youtube.com>
- 0.0663 | 2026-09-16 | step1 research_breakthrough nw=6 fit=6 | Learning to solve hard problems in RL for LLMs by never giving up <mnoukhov.github.io>
- 0.0710 | 2026-09-11 | step1 tech_history nw=2 fit=5 | Commander Keen's Adaptive Tile Refresh <fabiensanglard.net>
- 0.0773 | 2026-09-21 | step1 historical_archive nw=2 fit=5 | Lifestreams: a storage model for personal data (1996) <ee.columbia.edu>
- 0.0774 | 2026-09-06 | step1 historical_archive nw=1 fit=5 | The Ironies of Automation (1983) <static1.squarespace.com>

# gaming
trained on C:/Users/user/AppData/Local/Temp/news_copy.db decisions 2026-08-06..2026-08-23: 2008 junk / 1178 kept

## sanity: old test window 2026-08-24..2026-09-03 (AUC 0.901)
| target loss | threshold | kept lost | junk dropped | Step 1 input removed |
|---|---|---|---|---|
| 0.00% | 0.0049 | 0/744 (0.00%) | 11.10% | 6.60% |
| 0.10% | 0.0162 | 0/744 (0.00%) | 23.03% | 13.69% |
| 0.25% | 0.0179 | 0/744 (0.00%) | 24.59% | 14.61% |
| 0.50% | 0.0300 | 3/744 (0.40%) | 33.03% | 19.79% |
| 1.00% | 0.0622 | 12/744 (1.61%) | 45.41% | 27.64% |
| 2.00% | 0.0942 | 17/744 (2.28%) | 50.83% | 31.13% |

## NEW records 2026-09-03..2026-09-23 from logs/news.db: 2257 junk / 1476 kept, **AUC 0.884** (no refit)
| target loss | threshold | kept lost | junk dropped | Step 1 input removed |
|---|---|---|---|---|
| 0.00% | 0.0049 | 1/1476 (0.07%) | 5.76% | 3.51% |
| 0.10% | 0.0162 | 1/1476 (0.07%) | 14.75% | 8.95% |
| 0.25% | 0.0179 | 2/1476 (0.14%) | 15.55% | 9.46% |
| 0.50% | 0.0300 | 4/1476 (0.27%) | 21.93% | 13.37% |
| 1.00% | 0.0622 | 14/1476 (0.95%) | 32.88% | 20.25% |
| 2.00% | 0.0942 | 18/1476 (1.22%) | 39.30% | 24.24% |

### per week at the chosen point (target 0.25%, threshold 0.0179)
| week from | junk / kept | AUC | kept lost | junk dropped | input removed |
|---|---|---|---|---|---|
| 2026-09-03 | 780 / 477 | 0.865 | 1 (0.21%) | 13.85% | 8.67% |
| 2026-09-10 | 715 / 547 | 0.894 | 0 (0.00%) | 16.92% | 9.59% |
| 2026-09-17 | 762 / 452 | 0.893 | 1 (0.22%) | 16.01% | 10.13% |

### kept items that would be dropped at the chosen point
- 0.0041 | 2026-09-18 | step1 review nw=3 fit=6 | Akiba Lost Review <rpgsite.net>
- 0.0166 | 2026-09-03 | step1 major_announcement nw=3 fit=5 |  Acer's latest concepts asks, "do want your handheld to flip into a laptop," and my bad eyes say no  <gamesradar.com>
