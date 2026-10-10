
## tech — test 2026-08-24..: 3819 junk / 775 kept, AUC 0.901; threshold source: 1805 kept pre-test (OOF)
| target loss | threshold | kept lost on test | junk dropped | Step 1 input removed |
|---|---|---|---|---|
| 0.00% | 0.0123 | 0/775 (0.00%) | 5.68% | 4.72% |
| 0.10% | 0.0210 | 0/775 (0.00%) | 10.47% | 8.71% |
| 0.25% | 0.0422 | 1/775 (0.13%) | 19.69% | 16.39% |
| 0.50% | 0.0811 | 1/775 (0.13%) | 31.63% | 26.32% |
| 1.00% | 0.1041 | 4/775 (0.52%) | 37.08% | 30.91% |
| 2.00% | 0.1671 | 9/775 (1.16%) | 48.36% | 40.40% |

oracle on test (threshold set just below the k-th lowest kept item):
- lose 0/775 (0.00%): junk dropped 12.10%
- lose 1/775 (0.13%): junk dropped 32.97%
- lose 2/775 (0.26%): junk dropped 34.83%
- lose 4/775 (0.52%): junk dropped 38.26%
- lose 8/775 (1.03%): junk dropped 46.61%

## gaming — test 2026-08-24..: 1056 junk / 727 kept, AUC 0.905; threshold source: 1389 kept pre-test (OOF)
| target loss | threshold | kept lost on test | junk dropped | Step 1 input removed |
|---|---|---|---|---|
| 0.00% | 0.0050 | 0/727 (0.00%) | 12.69% | 7.52% |
| 0.10% | 0.0095 | 0/727 (0.00%) | 18.56% | 10.99% |
| 0.25% | 0.0160 | 0/727 (0.00%) | 25.19% | 14.92% |
| 0.50% | 0.0271 | 2/727 (0.28%) | 33.43% | 19.91% |
| 1.00% | 0.0691 | 12/727 (1.65%) | 47.35% | 28.72% |
| 2.00% | 0.1001 | 20/727 (2.75%) | 53.03% | 32.53% |

oracle on test (threshold set just below the k-th lowest kept item):
- lose 0/727 (0.00%): junk dropped 27.84%
- lose 1/727 (0.14%): junk dropped 28.12%
- lose 2/727 (0.28%): junk dropped 33.52%
- lose 4/727 (0.55%): junk dropped 37.78%
- lose 8/727 (1.10%): junk dropped 40.15%
