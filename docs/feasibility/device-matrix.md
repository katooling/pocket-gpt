# Device Matrix (Phase 0 + MVP Launch Policy)

## Purpose

Define representative devices for feasibility benchmarking by platform and class.

## iOS Targets

| Class | Representative SoC | RAM Class | Expected Default Model | Notes |
|---|---|---|---|---|
| Low | A15-class | 6 GB | 0.8B Q4 | strict context cap |
| Mid | A16/A17-class | 8 GB | 0.8B/2B Q4 | dynamic routing |
| High | A17 Pro/M-class iPad | 8-16 GB | 2B Q4 | optional premium mode |

## Android Targets

| Class | Representative SoC | RAM Class | Expected Default Model | Notes |
|---|---|---|---|---|
| Low | Snapdragon 7xx equivalent | 6 GB | 0.8B Q4 | conservative limits |
| Mid | Snapdragon 8 Gen 1/2 equivalent | 8-12 GB | 0.8B/2B Q4 | baseline target |
| High | Snapdragon 8 Gen 3 equivalent | 12-16 GB | 2B Q4 | optimization candidate |

## Test Matrix

Each class should run:

1. text-only benchmark set (`0.8B`)
2. text-only benchmark set (`2B`)
3. image input benchmark set (`0.8B`)
4. image input benchmark set (`2B`) if class budget allows

## Pass/Fail Threshold Inputs

Thresholds are finalized in `benchmark-protocol.md`.

## MVP Launch Device Policy (PROD-02)

Status: Finalized lock pass on 2026-03-04 (WP-03 confirmed Done on execution board).

### Decision Table - Required vs Best-Effort

| Launch Tier | Platform | Device Class | Required/Best-Effort | Measurable Launch Criteria | Decision Rule |
|---|---|---|---|---|---|
| Tier R1 | Android | Mid (Snapdragon 8 Gen 1/2, 8-12 GB RAM) | Required | Scenario A/B/C threshold PASS; soak test 30 minutes no repeatable blocker OOM/ANR; crash-free benchmark sessions >= 95% | Launch blocked if criteria fail on all R1 validation devices |
| Tier R2 | Android | High (Snapdragon 8 Gen 3, 12-16 GB RAM) | Required | Same as Tier R1 plus no routing regression vs Mid class in policy tests | Launch blocked if both R1 and R2 fail policy/threshold compliance |
| Tier B1 | Android | Low (Snapdragon 7xx eq., 6 GB RAM) | Best-Effort | Scenario A/B executed with evidence; graceful downgrade path verified under battery/thermal constraints; known limitations documented | Launch not blocked; public support language must state performance variability |
| Tier B2 | iOS (all classes) | A15/A16/A17/M-class | Best-Effort (post-MVP horizon) | Feasibility data only in MVP phase; no launch SLA commitments | Not part of MVP launch gate |

### Enforcement Notes

1. Required tiers define launch readiness gates and must be validated with physical-device evidence.
2. Best-effort tiers may ship with documented caveats and do not block MVP go/no-go.
3. Any policy change that reclassifies a tier requires updates to:
   - `docs/roadmap/mvp-beta-go-no-go-packet.md`
   - `docs/product/open-questions-log.md`
   - `docs/operations/execution-board.md`
