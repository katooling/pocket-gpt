# ENG-12 Product Decision: Model Distribution Path + Provenance Policy

Date: 2026-03-04  
Owner: Product Lead  
Decision status: Approved

## Decision

1. Default model distribution path for ENG-12: **side-load (manual/internal only)**.
2. PAD and first-launch network download are **not** part of the current ENG-12 implementation scope.

## Provenance + Load Policy (reliability-first)

The app must not load model artifacts unless all checks pass:

1. Artifact manifest entry exists for model id + version.
2. SHA-256 checksum exactly matches expected manifest value.
3. Artifact provenance signature/issuer check passes against trusted internal source.
4. File format/version compatibility check passes for runtime.

## Failure Policy

1. Any failed provenance/integrity check => **hard block model load** and show deterministic user-safe error.
2. No silent fallback to unverified artifacts.
3. Keep last known-good verified artifact available when present.

## Why this choice

1. Minimizes distribution complexity in current phase.
2. Reduces bug surface from multi-path delivery.
3. Improves successful loads in internal beta by enforcing one deterministic validated path.

## ENG-12 Unblock Statement

Product decision input for model distribution path/provenance policy is complete; ENG-12 implementation can proceed.
