# OpenCode v2 migration

AndCode currently speaks OpenCode **v1** (`1.18.x` musl binaries from GitHub Releases, root
REST routes, `prompt_async`, `global/event`). OpenCode **v2** (`2.x`) intentionally breaks the
server API, the plugin API, and parts of the config shape
([migrate guide](https://opencode.ai/v2/docs/migrate-v1/)). This document tracks the migration.

## Status

- [x] Phase 1 (this branch): additive v2 API layer + npm-based v2 release resolution. No v1
  behavior changed; the app still builds and runs against v1 exactly as before.
- [ ] Phase 2: port `OpenCodeBackend` implementations to the v2 client, port event handling
  (permissions, forms, streaming), verify against a live 2.x server.
- [ ] Phase 3: v2 on-device runtime (manifest, updater with sha512 verification, v2 config
  shapes), then drop the v1 client.

## Phase 1 contents

- `core/api/OpenCodeV2ApiModels.kt` — lenient v2 models (`V2Session`, `V2Message`, providers,
  agents, models, permission requests, `V2Event`).
- `core/api/OpenCodeV2ApiClient.kt` — `/api/*` client: `info`, sessions CRUD, `prompt`
  (inbox-based, replaces `prompt_async`), `interrupt`, permission list/reply, providers, agents,
  models, `api/event` SSE.
- `core/api/OpenCodeV2EventParser.kt` — v2 frame parser (`{id, event, data}` with JSON-encoded
  `data`). Event names are provisional; confirm against a live server in Phase 2.
- `runtime/local/NpmOpenCodeReleaseClient.kt` — v2 version discovery via the npm registry
  (`@opencode/cli/latest` + `@opencode/cli-linux-{arm64,x64}-musl`), using `dist.integrity`
  (`sha512-*`) instead of GitHub asset digests.
- Unit tests for all of the above (`MockWebServer`, no network).

## Endpoint map (v1 → v2)

| v1 | v2 | Notes |
|---|---|---|
| `GET global/health` | `GET /api/info` | `{version, pid, ...}`, no `healthy` flag |
| `GET session` | `GET /api/session` | `{data, cursor}`; archive marker still `time.archived` |
| `POST session` | `POST /api/session` | `{title?, agent?, model?}` → `{data}` |
| `GET session/{id}/message` | `GET /api/session/{id}/message` | `{data: [...]}` union; text extracted best-effort |
| `POST session/{id}/prompt_async` | `POST /api/session/{id}/prompt` | `{text, ...}` → inbox entry; progress via events/inbox |
| `POST session/{id}/abort` | `POST /api/session/{id}/interrupt` | |
| `POST session/{id}/permissions/{pid}` `{response}` | `POST .../permission/{rid}/reply` `{decision}` | `once/always/reject` values unchanged |
| `GET/POST question/...` | forms API (`/api/session/{id}/form...`) + `form` events | Not yet ported; biggest UX gap for Phase 2 |
| `GET provider` / `GET provider/auth` / `PUT /auth/{id}` | `GET /api/provider` + `integration`/`credential` routes | Auth flow redesign; verify OAuth against live server |
| `GET agent` | `GET /api/agent` | `{location, data}`; `mode: subagent/primary/all` |
| `GET global/event` + `event` fallback | `GET /api/event` | Single stream, no fallback |
| `GET mcp...` / `GET config...` / `GET command` / `GET skill` | `/api/mcp`, `/api/config`, `/api/command`, `/api/skill` | Phase 2 |

## Distribution decisions (v2 runtime)

- GitHub Releases only carry the v1 line (`1.18.x` latest). V2 ships via `opencode.ai/files/bin/`
  and npm (`@opencode/cli` + `@opencode/cli-linux-*-musl` optionals, currently `2.0.18`).
- No checksum files are published next to `files/bin`; the npm registry gives per-version
  `dist.integrity` (`sha512`), so the v2 updater verifies sha512 instead of the v1 SHA-256-hex
  model (`VerifiedRuntimeDownloader` + `LocalRuntimeReleaseAsset` need a Phase 3 extension).
- The on-device Alpine rootfs still wants the `musl` asset — npm has it
  (`cli-linux-arm64-musl`, `cli-linux-x64-musl`).
- `serve --hostname 127.0.0.1 --port <port>` is unchanged in v2, so
  `LocalRuntimeProcessLauncher` needs no flag changes.

## Config shapes to port in Phase 3

- `mcp.<name> {enabled, timeout: number}` → `mcp.servers.<name> {disabled, timeout: {catalog, execution}}`
  (`LocalRuntimeInstaller` browser/schedule entries).
- `provider.<id> {npm, options.baseURL}` → `providers.<id> {package: "aisdk:...", settings.baseURL}`
  (`CustomProviderStore`).
- `permission`/`tools` maps → ordered `permissions[]` with `shell` (not `bash`).
- `auth.json` `{provider: {type: "api", key}}` — confirm against v2 credential storage on-device.
