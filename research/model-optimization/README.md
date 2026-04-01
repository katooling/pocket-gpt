# Pocket GPT Model Optimization Research Hub

## Objective

This directory is the shared workspace for the research phase of the Pocket GPT optimization program.

The research phase must answer four questions with evidence:

1. What optimization mechanisms already exist in Pocket GPT?
2. Which of those mechanisms are incorrect, fragile, incomplete, or poorly tuned?
3. Which high-value optimizations are missing relative to current best practice and relevant peer projects?
4. How should we introduce or repair those optimizations with measurable wins and low regression risk?

## Target End State

By the end of this phase, we should have:

- a verified inventory of Pocket GPT optimization features and parameters
- a gap analysis against best practice, peer projects, and relevant research
- a benchmark and validation strategy that can prove improvements across device classes
- a staged implementation plan with owners, evidence requirements, and rollback criteria

## Quick Workspace Overview

- `pocket-gpt/`
  - Target application.
  - Android/Kotlin app with modular KMP packages and a `llama.cpp` JNI runtime path.
  - Existing surfaces include GPU offload, speculative decoding, prefix caching, runtime profiles, model routing, GGUF handling, and benchmark harnesses.
- `pocketpal-ai/`
  - Closest mobile comparator for on-device chat UX, model management, and benchmark practices.
- `ollama/`
  - Strong comparator for runner architecture, model loading semantics, cache/runtime discipline, and backend/runtime evolution.
- `lms/`, `lmstudio-js/`, `mlx-engine/`
  - Useful comparators for load-time parameter surfaces, local runtime control, speculative decoding, and Apple-side inference patterns.

## Operating Rules

1. Every team writes only to its assigned files or subdirectory.
2. Every finding must include:
   - current state
   - evidence
   - risk or gap
   - recommendation
   - open questions
3. Local code findings should reference concrete repo paths.
4. External findings should capture source title, date accessed, URL, and why the source matters.
5. Recommendations are not rollout-ready until validation requirements are defined.
6. Cross-team disagreements and unresolved questions belong in `logs/decision-log.md`.

## Directory Map

- `org-structure.md`
  - Team formation options and chosen org design.
- `master-plan.md`
  - Senior-lead synthesis and phased rollout plan.
- `logs/decision-log.md`
  - Shared decision log, open questions, and cross-team blockers.
- `templates/finding-template.md`
  - Common format for findings.
- `findings/native-runtime/`
  - C++/JNI runtime, backend bring-up, load/generation parameters, cache paths, speculative decoding.
- `findings/runtime-policy/`
  - Kotlin runtime policy, tuning, routing, memory estimation, configuration mapping.
- `findings/comparative-systems/`
  - PocketPal, Ollama, LM Studio, and MLX Engine comparisons.
- `findings/external-research/`
  - Official docs, libraries, best practices, and papers.
- `findings/benchmarking-validation/`
  - Benchmark harness audit, measurement strategy, device matrix, rollout gates.

## Immediate Deliverables

1. Team leads create scoped engineer pods and assign disjoint outputs.
2. Each team produces a lead summary and engineer notes.
3. Senior lead consolidates findings into `master-plan.md`.
