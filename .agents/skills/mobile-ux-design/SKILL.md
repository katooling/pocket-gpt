---
name: designing-mobile-ux
description: Guides PocketGPT mobile UX improvements for touch-first Android flows including model selection, download, load, remove, retry, and recovery. Triggers on UX review, Compose screen design, bottom sheet, dialog, empty state, loading state, destructive action, error handling, model management UI, or accessibility.
---

# Mobile UX Design for PocketGPT

## Core Principles

1. **One clear primary action** per surface
2. **Immediate feedback** after every tap — never close a dialog with no visible outcome
3. **Safe destructive flows** — explain side effects before confirm, offer undo after
4. **Recovery over dead ends** — say what failed, offer the next best action

## Destructive Actions

- Explain exactly what will happen and mention side effects (unloading, clearing selection, needing re-download)
- Block impossible actions before the user commits; if blocked, say why
- Visually separate destructive buttons from primary actions (own row, error tint, divider)
- After commit: show snackbar with undo, then reflect the new state immediately

## Error and Recovery

- Say what failed before saying what to do next
- Preserve user context — don't silently switch models or behavior
- Offer concrete actions: `Retry`, `Choose another model`, `Refresh`, `Import`

## Compose Guidance

### Status Hierarchy (show highest-priority only)

`Loaded` > `Loading`/`Switching` > `Default` > `Active` > `Ready` > `Error`

### Bottom Sheets

- Current state at top, grouped related actions, destructive actions visually separated
- Status text close to the controls it describes

### Dialogs

- Title = the action. Body = one-paragraph consequence. Confirm button = real verb (`Remove`, not `OK`)
- If action also unloads or clears selection, say so explicitly

### Loading / Error States

- Show progress for long operations. Keep previous model name visible during loading.
- On failure: show selected model + next step
- If GPU acceleration was reduced or a model was auto-restored, tell the user in the same surface

## Terminology

These must mean exactly one thing each:

- `Default` = product-recommended starter model
- `Active` = user-selected version for a model
- `Loaded` = currently in runtime memory

## Model Management Checklist

```
- [ ] Choosing: recommended model is obvious
- [ ] Selecting: active version and product default are clearly different
- [ ] Loading: user can tell what is loading
- [ ] Downloading: progress, pause, resume, retry, cancel visible
- [ ] Removing: side effects explained before confirm; undo available after
- [ ] Failure: explanation + recovery action
- [ ] Fallback: no silent behavior changes
```

## Copy Style

- Short, concrete sentences. Say `what happened` before `what to do next`.
- No internal implementation terms. No blame copy (`invalid request`).

Good: `Couldn't remove this model because it is still active. Set another version active first.`
Bad: `Operation failed`

## Anti-Patterns

- Same badge label meaning different things in different contexts
- Confirmation dialog dismisses before app reacts
- Silent retry or fallback without user-visible messaging
- Destructive button placed next to primary action with equal visual weight
