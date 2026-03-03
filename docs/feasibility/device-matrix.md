# Device Matrix (Phase 0)

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
