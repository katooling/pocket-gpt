# Android UI Shell Cleanup

## Why this exists

Recent UX work improved several leaf composables, but the shell remained the main source of drift:

- visible UI controls could compile with no wiring
- modal behavior lived in one large composition root
- onboarding/resource migrations could be half-finished
- shell-only concerns were mixed with view rendering and provisioning workflows

This cleanup keeps shell work isolated from ongoing runtime and quantization changes.

## Verified learnings

- `ChatApp.kt` is the Android UI composition root.
- `ChatViewModel` owns durable chat state.
- `ChatAppViewModel` owns transient shell orchestration state.
- `ModalSurface` is the right navigation primitive for modal UI in this app.
- leaf composables are healthiest when they stay state + intent only.

## Cleanup checklist

- Keep shell-only logic in shell-only files.
- Make visible controls require real callbacks when they are not optional.
- Keep optimistic delete and snackbar undo above leaf drawers/lists.
- Keep connectivity observation out of screen content composables.
- Keep onboarding on a single implementation and a single string contract.
- Run `compileDebugKotlin` before considering UI work integrated.

## Safe extractions

These are safe because they only touch the Android UI shell layer:

1. `PocketAgentTopBar`
   Moves app-bar model switching and settings/session actions out of `ChatApp`.
2. `SessionDeleteUndoState`
   Owns optimistic session hiding and snackbar undo.
3. `ShellConnectivity`
   Owns connectivity-to-UI translation for the offline chip.
4. `ModalOrchestrator`
   Owns modal rendering for tool dialog, settings sheets, onboarding, and shell confirmation dialogs.

## Next extractions

These should happen after the current UX shell cleanup is stable:

1. Extract the `ModelSheet` event lambda from `ChatApp`.
2. Add a dedicated shell action model for the top app bar.
3. Split download transition side effects from `ChatApp`.
4. Add a small compile-only UI integration lane to catch shell/leaf drift early.

## Guardrails

- Do not couple shell cleanup to ongoing quantization/runtime work.
- Do not move business logic from `ChatViewModel` into UI composables.
- Prefer extracting cohesive shell behaviors over introducing broad UI base classes.
