# Jev pre-filter benchmark — 1500 answered items
total cost $0.0000, latency p50 25ms p90 31ms, avg input tokens 1125

## Separation (AUC: covered above rejected; 0.5 = coin flip)
| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |
|---|---|---|---|---|
| gaming | 250 / 250 | 0.539 | 0.597 | 0.998 |
| tech | 250 / 250 | 0.612 | 0.655 | 0.999 |
| science | 250 / 250 | 0.503 | 0.672 | 1.000 |
| ALL | 750 / 750 | 0.549 | 0.638 | 0.998 |

### keep — drop when keep < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.05 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.1 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.2 | 0.0% (0/250) | 0.4% (1/250) | 0.0% |
| gaming | 0.3 | 2.4% (6/250) | 3.2% (8/250) | 42.9% |
| gaming | 0.5 | 54.8% (137/250) | 43.6% (109/250) | 55.7% |
| tech | 0.05 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.1 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.2 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.3 | 6.4% (16/250) | 6.0% (15/250) | 51.6% |
| tech | 0.5 | 72.4% (181/250) | 58.8% (147/250) | 55.2% |
| science | 0.05 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.1 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.2 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.3 | 2.8% (7/250) | 0.4% (1/250) | 87.5% |
| science | 0.5 | 74.0% (185/250) | 74.8% (187/250) | 49.7% |

### fit — drop when fit < t
| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |
|---|---|---|---|---|
| gaming | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| gaming | 0.75 | 0.4% (1/250) | 0.0% (0/250) | 100.0% |
| gaming | 1 | 0.4% (1/250) | 0.0% (0/250) | 100.0% |
| tech | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.5 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 0.75 | 0.0% (0/250) | 0.0% (0/250) | - |
| tech | 1 | 0.8% (2/250) | 0.0% (0/250) | 100.0% |
| science | 0.3 | 0.0% (0/250) | 0.0% (0/250) | - |
| science | 0.5 | 0.4% (1/250) | 0.0% (0/250) | 100.0% |
| science | 0.75 | 0.4% (1/250) | 0.0% (0/250) | 100.0% |
| science | 1 | 1.6% (4/250) | 0.0% (0/250) | 100.0% |

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
| other | 76 | 53 |
| review | 45 | 30 |
| announcement | 24 | 33 |
| release_date | 14 | 33 |
| entertainment_offtopic | 27 | 10 |
| community_story | 12 | 23 |
| opinion_analysis | 22 | 6 |
| rumor_leak | 3 | 18 |
| update_patch | 7 | 12 |
| security_incident | 2 | 11 |
| research_result | 4 | 6 |
| deal_promo | 7 | 1 |
| business | 0 | 7 |
| quote_interview | 2 | 3 |
| guide_howto | 4 | 0 |
| listicle_roundup | 1 | 3 |
| policy_regulation | 0 | 1 |

### tech
| kind | rejected | covered |
|---|---|---|
| other | 100 | 73 |
| security_incident | 18 | 50 |
| announcement | 26 | 40 |
| research_result | 34 | 29 |
| release_date | 5 | 17 |
| review | 15 | 2 |
| policy_regulation | 6 | 7 |
| business | 2 | 11 |
| opinion_analysis | 11 | 0 |
| deal_promo | 7 | 3 |
| update_patch | 2 | 8 |
| listicle_roundup | 7 | 2 |
| entertainment_offtopic | 8 | 0 |
| community_story | 2 | 3 |
| rumor_leak | 3 | 2 |
| quote_interview | 3 | 1 |
| guide_howto | 1 | 2 |

### science
| kind | rejected | covered |
|---|---|---|
| research_result | 81 | 191 |
| other | 133 | 35 |
| announcement | 12 | 6 |
| security_incident | 1 | 8 |
| review | 7 | 0 |
| policy_regulation | 0 | 7 |
| release_date | 4 | 1 |
| rumor_leak | 2 | 2 |
| guide_howto | 3 | 0 |
| update_patch | 2 | 0 |
| listicle_roundup | 2 | 0 |
| opinion_analysis | 2 | 0 |
| deal_promo | 1 | 0 |

## Disagreements worth eyeballing

### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops

### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)
