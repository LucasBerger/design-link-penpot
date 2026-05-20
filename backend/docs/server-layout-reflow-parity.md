# Server layout reflow parity contract

## Parity target

Backend layout reflow matches the **frontend commit path**, not live preview or WASM-only internals.

| Layer | Reference | Used by server reflow |
|-------|-----------|----------------------|
| Modifier tree | `ctm/reflow-modifiers` | Yes |
| Geometry engine | `app.common.geom.modifiers/set-objects-modifiers` | Yes |
| Shape application | `gsh/transform-shape` + `transform-attrs` allowlist | Yes |
| Change generation | `app.common.logic.shapes/generate-update-shapes` + `pcb/resize-parents` | Yes |
| Persistence | `update-file` lifecycle (`revn`/`vern`, locks, `file_change`, msgbus) | Yes |
| WASM preview | `apply-wasm-modifiers`, `render-wasm/v1` | **No** |

Frontend entry points mirrored on commit:

- `app.main.data.workspace.shape-layout/update-layout-positions`
- `app.main.data.workspace.modifiers/apply-modifiers` → `apply-modifiers*`

## Supported scopes

1. **Full page** — no `root-ids`: all shapes with `ctl/any-layout?` on the page, ordered by shape id (deterministic).
2. **Targeted roots** — `root-ids` provided: reflow only those ids (import workflows), same ordering.

Optional `page-id`; when omitted, the first page in the file is used.

## Determinism

- Layout roots sorted by `(str id)` before building the modifier tree.
- Modifier application order follows `set-objects-modifiers` (common engine).
- Output is minimal `:mod-obj` / `:reg-objects` diffs constrained to `transform-attrs`.

## No-op semantics

When computed geometry equals stored geometry, the RPC returns without calling `update-file*` (no `revn` bump, no webhooks).

## Out of scope

- `:reg-objects` alone (selrect repair for groups/bools without layout) — use explicit changes if needed.
- `repair-file` validation repair — different subsystem.
- Interactive drag/resize modifier construction — only `reflow-modifiers`.
