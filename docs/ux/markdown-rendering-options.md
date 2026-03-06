# Markdown Rendering Options (UX-CHAT-01)

Last updated: 2026-03-04
Owner: Android
Lifecycle: Implemented with lightweight renderer, library path documented

## Goal

Render assistant output with at least:

1. bold text
2. bullet lists
3. code block sections

## Options Researched

### Option A: `compose-markdown`

- Pros:
  - purpose-built markdown rendering for Compose
  - broad community usage
  - rich markdown feature support
- Cons:
  - adds dependency and API-surface maintenance
  - increases integration complexity during MVP stabilization

### Option B: `compose-richtext` (`richtext-markdown`/`richtext-commonmark`)

- Pros:
  - rich formatting ecosystem and modular design
  - good long-term extensibility
- Cons:
  - project currently marked experimental and pre-1.0
  - additional risk for MVP closure timeline

### Option C: In-app lightweight renderer (chosen for MVP)

- Pros:
  - no extra dependency risk
  - deterministic scope for MVP acceptance criteria
  - easy to test and evolve
- Cons:
  - limited markdown coverage compared to full parsers
  - future maintenance burden if feature scope expands

## Decision

Use an in-app lightweight renderer for MVP scope and revisit dependency adoption post-MVP once WP-12 is closed and UX stability is validated.

## Future Upgrade Trigger

Move to library-backed markdown parsing when any of the following are true:

1. tables/links/inline images are required in MVP+1.
2. parser edge cases exceed maintenance threshold.
3. design requires richer markdown fidelity across multiple screens.
