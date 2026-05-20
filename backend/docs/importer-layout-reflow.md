# Importer integration: `recompute-file-layout`

## When to call

After coarse geometry is written with `update-file` (HTML/VDT import), call **`recompute-file-layout`** once per page (or per layout root) so flex/grid/hug/fill/constraints match editor commit behavior.

## RPC

- **Method:** `recompute-file-layout`
- **Module:** files (same auth as `update-file`)

### Request

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | File UUID |
| `session-id` | yes | Session UUID |
| `revn` | yes | Current file revision (must match server) |
| `vern` | yes | Current file version token |
| `features` | no | Client feature flags |
| `page-id` | no | Page to reflow; default first page |
| `root-ids` | no | Targeted layout roots (max 100); default all `ctl/any-layout?` frames |
| `skip-validate` | no | Skip strict validation (same as `update-file`) |

### Response

| Field | Description |
|-------|-------------|
| `file-id` | File UUID |
| `revn` | Revision after apply (unchanged on no-op) |
| `vern` | Version token |
| `noop?` | `true` if geometry was already correct |
| `changed-count` | Number of `:mod-obj` changes applied |
| `root-count` | Layout roots processed |

## Recommended flow

```
1. update-file     — upload shapes / layout attrs
2. recompute-file-layout — reflow scoped page/roots
3. update-file     — further edits using returned revn
```

Use the **`revn` from the response** for subsequent writes. On `noop? true`, `revn` is unchanged.

## Limits

- Max **100** `root-ids` per call (`:reflow-too-many-roots`)
- Max **10000** changed shapes (`:reflow-too-many-changes`)
- Shares rate limits with `update-file` climit buckets

## Metrics (Prometheus)

- `penpot_rpc_recompute_file_layout_roots_total`
- `penpot_rpc_recompute_file_layout_changed_total`
- `penpot_rpc_recompute_file_layout_noop_total`
- `penpot_rpc_recompute_file_layout_applied_total`

## Parity reference

See [server-layout-reflow-parity.md](./server-layout-reflow-parity.md).
