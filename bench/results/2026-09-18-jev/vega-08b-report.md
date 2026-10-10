# Jev pre-filter benchmark — 1500 answered items
total cost $0.0000, latency p50 158ms p90 200ms, avg input tokens 1674

## Separation (AUC: covered above rejected; 0.5 = coin flip)
| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |
|---|---|---|---|---|
| gaming | 250 / 250 | 0.489 | 0.672 | 0.998 |
| tech | 250 / 250 | 0.446 | 0.540 | 0.999 |
| science | 250 / 250 | 0.500 | 0.677 | 1.000 |
| ALL | 750 / 750 | 0.483 | 0.612 | 0.998 |

### keep — drop when keep < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.05 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.1 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.2 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.05 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.1 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.2 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.05 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.1 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.2 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |

### fit — drop when fit < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.75 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 1 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.75 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 1 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.75 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 1 | 0.0% (0/250) | 0.0% (0/250) | - |

### combined — drop when keep < 0.2 AND fit < 0.75
| category | junk dropped | lost news | precision |
|---|---|---|---|
| gaming | 0.0% | 0.0% | 0.0% |
| tech | 0.0% | 0.0% | 0.0% |
| science | 0.0% | 0.0% | 0.0% |

## Kind distribution (Jev) by Step 1 verdict

### gaming
| kind | rejected | covered |
|---|---|---|
| other | 156 | 132 |
| update_patch | 69 | 82 |
| announcement | 3 | 16 |
| review | 7 | 7 |
| community_story | 4 | 6 |
| guide_howto | 8 | 1 |
| quote_interview | 2 | 4 |
| release_date | 1 | 0 |
| policy_regulation | 0 | 1 |
| opinion_analysis | 0 | 1 |

### tech
| kind | rejected | covered |
|---|---|---|
| update_patch | 107 | 135 |
| other | 123 | 96 |
| quote_interview | 3 | 4 |
| announcement | 1 | 6 |
| review | 4 | 2 |
| guide_howto | 6 | 0 |
| opinion_analysis | 4 | 1 |
| policy_regulation | 1 | 3 |
| research_result | 0 | 2 |
| release_date | 1 | 0 |
| security_incident | 0 | 1 |

### science
| kind | rejected | covered |
|---|---|---|
| other | 152 | 122 |
| research_result | 16 | 89 |
| update_patch | 52 | 35 |
| quote_interview | 10 | 1 |
| guide_howto | 10 | 0 |
| opinion_analysis | 5 | 1 |
| review | 4 | 0 |
| announcement | 1 | 1 |
| policy_regulation | 0 | 1 |

## Disagreements worth eyeballing

### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops

### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)
- [tech] keep=0.9374 fit=1.9764 kind=other | step1 discussion nw=0 fit=1 | UrgenT Help Detecting Performance Regressions Using Machine Learning and Hardware Counters <https://www.reddit.com/r/MachineLearning/comments/1vngjmv/urgent_help_detecting_performance_regressions>
- [tech] keep=0.9084 fit=1.6484 kind=other | step1 devtool_release nw=5 fit=5 | Muse Glimmer on RX 7600 XT 16GB <https://www.reddit.com/r/LocalLLaMA/comments/1vloras/muse_glimmer_on_rx_7600_xt_16gb>
- [science] keep=0.9017 fit=1.8794 kind=other | step1 opinion nw=0 fit=0 | The three-and-beyond-body problem: juggling kids, travel and careers in academia <https://www.nature.com/articles/d41586-026-01845-6>
- [science] keep=0.9286 fit=1.8915 kind=other | step1 minor_telescope_image nw=1 fit=2 | APOD: 2026 August 8 – A Messier Moment for Tempel 2 <https://science.nasa.gov/image-article/apod/apod-2026-august-8-a-messier-moment-for-tempel-2>
- [science] keep=0.9026 fit=1.9656 kind=other | step1 routine_iss_activity nw=1 fit=1 | NASA Sets Spacewalk for Station Maintenance, Live Coverage Planned <https://www.nasa.gov/news-release/nasa-sets-spacewalk-for-station-maintenance-live-coverage-planned>
- [science] keep=0.9117 fit=2.0243 kind=other | step1 space_mission nw=4 fit=4 | NASA’s Lunar Development and Test Facility Prepares Artemis Hardware for Moon  <https://www.nasa.gov/centers-and-facilities/johnson/nasas-lunar-development-and-test-facility-prepares-artemis-hardware-for-moon>
- [science] keep=0.9051 fit=1.6943 kind=guide_howto | step1 how_to nw=1 fit=1 | Fix your terrible overhead lights—no electrician required <https://www.popsci.com/diy/how-to-upgrade-overhead-light-lamps>
- [science] keep=0.9536 fit=2.0027 kind=update_patch | step1 software_update nw=1 fit=1 | TB 26-04 Updates and Modernization of NASA’s Chemical Equilibrium with Applications (CEA)  <https://www.nasa.gov/centers-and-facilities/nesc/tb-26-04-updates-and-modernization-of-nasas-chemical-equilibrium-with-applications-cea-code>
- [science] keep=0.9124 fit=1.9619 kind=update_patch | step1 evergreen_explainer nw=2 fit=2 | What Is El Niño? Here’s What It Means for Weather, Water, and Global Economy <https://www.wired.com/story/what-el-nino-means-weather-water-global-economy>
- [science] keep=0.9204 fit=1.9715 kind=update_patch | step1 speculative_future_claim nw=2 fit=2 | Will you be wearing an exoskeleton in ten years? The tech that could change how we move <https://www.nature.com/articles/d41586-026-02527-z>
- [science] keep=0.9196 fit=1.8996 kind=other | step1 correction_retraction nw=1 fit=1 | Author Correction: Plasticity and language in the anaesthetized human hippocampus <https://www.nature.com/articles/s41586-026-11071-9>
- [science] keep=0.9272 fit=1.7612 kind=other | step1 minor_animal_story nw=2 fit=2 | Bald eaglets Sandy and Luna leave Big Bear Valley, Shadow gets a little vacation <https://www.popsci.com/environment/bald-eagles-sandy-luna-leave-big-bear-valley>
- [science] keep=0.9119 fit=1.8928 kind=update_patch | step1 consumer_tech_news nw=3 fit=3 | How Zipline’s drone delivery system works in U.S. suburbs <https://www.scientificamerican.com/article/how-ziplines-drone-delivery-system-works-in-u-s-suburbs>
- [science] keep=0.9258 fit=1.8925 kind=review | step1 product_review nw=1 fit=1 | The most approachable moto-style e-bike I’ve ridden <https://www.popsci.com/gear/super73-mzft-a-series-52v-compact-moto-style-e-bike-review>
- [science] keep=0.9029 fit=1.9404 kind=update_patch | step1 opinion nw=0 fit=1 | Amend copyright licences to halt AI misuse and reassert human control <https://www.nature.com/articles/d41586-026-02633-y>
- [science] keep=0.9248 fit=1.792 kind=other | step1 speculative_future_claim nw=4 fit=3 | Can QuantumScape and Factorial Energy mass-produce solid-state batteries? <https://www.scientificamerican.com/article/can-quantumscape-and-factorial-energy-mass-produce-solid-state-batteries>
- [science] keep=0.9256 fit=2.0171 kind=other | step1 university_press_release nw=1 fit=1 | NASA Brings Space Exploration to the FIFA World Cup  <https://www.nasa.gov/centers-and-facilities/johnson/nasa-brings-space-exploration-to-the-fifa-world-cup>
- [science] keep=0.9279 fit=2.0758 kind=other | step1 notable_image nw=1 fit=2 | Bountiful Roebuck Bay  <https://science.nasa.gov/earth/earth-observatory/bountiful-roebuck-bay>
- [science] keep=0.925 fit=1.9213 kind=update_patch | step1 consumer_tech_news nw=2 fit=2 | A California Program Is Bringing Down the Cost of Heat Pumps by Buying Bulk <https://www.wired.com/story/california-group-buy-bringing-down-heat-pump-costs>
- [science] keep=0.9091 fit=2.0619 kind=other | step1 explainer nw=1 fit=1 | Growth hormone peptides promise to stall aging—but does the science stack up? <https://www.scientificamerican.com/article/growth-hormone-peptides-promise-to-stall-aging-but-does-the-science-stack-up>
- [science] keep=0.9207 fit=2.0266 kind=update_patch | step1 ai_for_science nw=4 fit=4 | Volunteer Develops Machine-Learning Tool to Identify Rare Clouds <https://science.nasa.gov/get-involved/citizen-science/volunteer-develops-machine-learning-tool-to-identify-rare-clouds>
- [science] keep=0.903 fit=1.7811 kind=quote_interview | step1 explainer nw=1 fit=2 | Can apps really make you fluent in another language? <https://www.popsci.com/science/do-language-apps-duolingo-work>
- [science] keep=0.9208 fit=1.988 kind=other | step1 interview_without_news nw=2 fit=2 | Total solar eclipse, vaccine controversy and coffee health benefits <https://www.scientificamerican.com/podcast/episode/total-solar-eclipse-vaccine-controversy-and-coffee-health-benefits>
- [science] keep=0.9202 fit=1.929 kind=other | step1 space_mission nw=3 fit=3 | We just got a glimpse at what NASA’s Skyfall helicopters will look like in action <https://www.popsci.com/science/nasa-skyfall-helicopters-mars>
- [science] keep=0.9267 fit=1.9004 kind=guide_howto | step1 explainer nw=1 fit=1 | How Americans stayed cool before air conditioning: From sitting on smelly ice to sleeping  <https://www.popsci.com/technology/before-air-conditioning-history>
