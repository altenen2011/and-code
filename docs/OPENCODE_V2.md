# OpenCode v2 migration

AndCode currently speaks OpenCode **v1** (`1.18.x` musl binaries from GitHub Releases, root
REST routes, `prompt_async`, `global/event`). OpenCode **v2** (`2.x`) intentionally breaks the
server API, the plugin API, and parts of the config shape
([migrate guide](https://opencode.ai/v2/docs/migrate-v1/)). This document tracks the migration.

## Status

- [x] Phase 1 (this branch): additive v2 API layer + npm-based v2 release resolution. No v1
  behavior changed; the app still builds and runs against v1 exactly as before.
- [x] Phase 2a (this branch): v2→v1 translation + `RemoteOpenCodeV2Backend` (chat core: sessions,
  messages, prompt with session agent/model switching, interrupt, permissions, command, events).
  Not wired into targets yet — the running app still uses the v1 backend.
- [ ] Phase 2b: wire targets to v2 (with version detection + v1 fallback), port files/vcs/
  projects/diff/todo/mcp/config/commands/skills/auth/forms, verify against a live 2.x server.
- [ ] Phase 3: v2 on-device runtime, then drop the v1 client.
  - [x] 3a (this branch): npm-channel manifest, sha512 verification, npm-tarball install,
    per-install serve password (env + profile), v2 `mcp.servers` provisioning, v2 `providers`
    custom-provider sync. Fresh installs on this build run OpenCode 2.0.18.
  - [ ] 3b: in-app 1.x→2.x updater (npm check + sha512 re-download + password backfill).
- [ ] Phase 2c: v2 provider auth UI (integration/credential API in Settings).

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

## Live-verified behavior (2.0.18 loopback server)

- `serve` requires auth (Basic `opencode` + password) even on loopback; `OPENCODE_SERVER_PASSWORD`
  is honored. The app generates a per-install password and sends it both as env and profile auth.
- Session/message times are epoch **milliseconds** (schema just says `number`).
- `POST prompt` returns the inbox entry; progress arrives as `session.text.delta` (with
  `assistantMessageID` + `ordinal`) and completion as `session.step.ended` (`finish: "stop"`).
- Failures surface as `session.retry.scheduled` (with `error.{type,message}`) and
  `retry` blocks on the assistant message — never as a dedicated error event.
- `PATCH session` answers `204` empty: rename re-reads the session. `DELETE` destroys (no
  archive route exists in v2 — archive stays unsupported and safely skipped).
- Tool calls emit `session.tool.*` + `shell.*` (no session id on shell frames); permissions
  default to allow on an unconfigured server.
- `switchAgent`/`switchModel` answer `204` empty. Prompt takes no agent/model (session-level).

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
