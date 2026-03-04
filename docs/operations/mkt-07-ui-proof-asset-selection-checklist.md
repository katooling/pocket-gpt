# MKT-07 UI Proof Asset Selection Checklist

Last updated: 2026-03-04  
Owner: Marketing + QA support

## Asset Eligibility Rule

An asset is eligible only if it maps to validated UI flow evidence and does not contain excluded/provisional claims.

## Approved Shot List

1. Shot A: Launch screen with offline indicator, composer, and send control.
   - Flow IDs: UI-01
   - Evidence refs: WP-11 QA-08 + WP-09 ENG-18
2. Shot B: Session drawer with create/switch/delete controls.
   - Flow IDs: UI-03/UI-04
   - Evidence refs: WP-11 + ENG-18 tests
3. Shot C: Tool action from dialog with deterministic result rendering.
   - Flow IDs: UI-06
   - Evidence refs: WP-09 run-01 maestro + unit tests
4. Shot D: Advanced controls sheet with routing selection and diagnostics export.
   - Flow IDs: UI-07/UI-08
   - Evidence refs: WP-09 run-01 maestro + instrumentation

## Asset Readiness Checklist

- [ ] Asset captured from current app build and dated.
- [ ] Claim/caption mapped to `UIC-*` ID in `mkt-07-ui-proof-messaging-map.md`.
- [ ] Evidence note + raw artifact links attached.
- [ ] No excluded/provisional claim text in caption/overlay.
- [ ] Product + QA review complete.

## Exclusions and Flags

1. Excluded:
   - any iOS-now wording
   - any voice-now wording
   - any universal performance guarantee wording
2. Provisional flag required:
   - claims implying broad low-tier physical-device parity until hardware coverage expands.
