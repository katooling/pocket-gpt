# Design System Basics (MVP)

Last updated: 2026-03-04
Owner: Android + Product

## Principles

1. Clarity over novelty.
2. Trust signals always visible (offline/runtime/error state).
3. One primary path per screen.
4. Accessibility parity for all critical actions.

## Visual Baseline

- Framework: Material3 Compose.
- Primary layout: single chat screen + drawer + sheets/dialogs.
- Message roles:
  - user: `primaryContainer`
  - assistant: `surfaceVariant`
  - system/error: `errorContainer`

## Spacing and Structure

1. Screen padding: 12dp in chat body.
2. Message bubble padding: 12dp.
3. Section spacing: 8dp between status chips and message list.
4. Suggested prompt cards: full-width cards in empty state.

## Typography Usage

1. `titleMedium` for section titles.
2. `labelSmall` for metadata (tool/image labels, status details).
3. `bodyMedium` for message content.
4. `bodySmall` for diagnostics and technical details.

## Interaction Rules

1. Composer send action disabled while sending.
2. Message list auto-scrolls to latest content during streaming.
3. Long content supports markdown-like rendering and copy action.
4. Tool invocations should be discoverable via natural-language prompts.

## Accessibility Baseline

1. All top-level controls require a11y content descriptions.
2. Routing mode controls use radio semantics.
3. Session list items communicate selected state.
4. Runtime error banner is consistently test-tagged and user-readable.
