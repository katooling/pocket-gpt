# Onboarding Specification (UX-ONBOARD-01)

Last updated: 2026-03-04
Owner: Android + Product
Lifecycle: Implemented (MVP v1)

## Objective

Explain privacy guarantees, capability surface, and runtime readiness in the first 30 seconds.

## Flow

1. **Page 1 - Privacy promise**
   - Message: chats and memory stay local by default.
2. **Page 2 - Capability preview**
   - Message: text chat, image analysis, and local tools run in one timeline.
3. **Page 3 - Runtime readiness**
   - Message: check runtime status before heavy prompts and open model setup if `Not ready`.

## Actions

- `Next` advances to next page.
- `Skip` closes onboarding immediately.
- `Get started` closes onboarding on final page.

## Persistence

- Onboarding state is persisted in session persistence (`onboardingCompleted`).
- Returning users should not see onboarding unless state is reset.

## UX Guardrails

1. Onboarding should never block core app rendering.
2. Onboarding text must avoid unsupported feature claims.
3. Any privacy wording must align with `docs/security/privacy-model.md`.
4. Runtime-readiness guidance must reference the in-app recovery path (`Advanced` -> `Open model setup`).
