# ExpressAssistant on ROG

## Goal

- Run the PC server independently of Android and Mac, reusing the migrated account sessions and cached packages. Keep pickup codes available through the web UI, REST and MCP.

## Current State

- Verified 2026-11-06 (America/Cuiaba): server 0.1.5 at http://100.106.169.46:18769, project C:\Users\Meta\source\repos\ExpressAssistant, %LOCALAPPDATA%/ExpressAssistantServer/data is the production data directory.
- Scheduled task `ExpressAssistant Server` runs as SYSTEM at startup, restarts on failure, has no execution time limit, and ignores duplicate starts. Windows was not rebooted for acceptance; task stop/start and service readiness were verified.
- Runtime uses existing uv-managed Python 3.12.13, project server/.venv, installed Chrome headless, and remote browser login mode. No global program/runtime installation.
- Full Mac data was transferred and all 7 source files compared byte-for-byte before startup. Original token, account IDs, cookies and origins were retained. JD/Taobao/PDD each have one account; Xiaomi has none.
- Live ROG sync: JD17, Taobao7, PDD130, Cainiao10, no channel errors; historical union197, shipped4/done193. A pending order replacement matched the same source/order ID. Real pickup code/physical station acceptance remains unverified.
- Mac service environment and private server data have been removed after successful ROG acceptance. Android signing, Android data and Cainiao research stay outside this deployment scope.
- Access token is in %LOCALAPPDATA%/ExpressAssistantServer/data/config.json and %LOCALAPPDATA%/ExpressAssistantServer/data/CONNECTION.md. Never commit credentials or server/data. `AGENTS.md`, `docs/log/`, and `server/runtime/` are in .git/info/exclude.

- 2026-11-23: normal SYSTEM console shutdown, production data moved after writer exit; startup/stop paths switched. Health199 rows retained. Use normal console shutdown for migration; old forced-stop helper is not the migration procedure.

## Active Work

- No remaining ROG migration acceptance blocker. Platform sessions can expire later; use Cookie import or remote login in the account UI.
- Real Windows reboot acceptance and Xiaomi login have not been performed.

## Build / Run / Test

- Production start: `Start-ScheduledTask -TaskName 'ExpressAssistant Server'`.
- Production stop: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\Users\Meta\source\repos\ExpressAssistant\server\runtime\Stop-Server.ps1`. This stops the task and both venv launcher/base-Python processes.
- Restart: run the stop script, then Start-ScheduledTask. Do not start a separate `uv run` writer against production data.
- Health: GET http://127.0.0.1:18769/api/health. Authenticated REST base is the same; MCP is POST /mcp.
- Logs: %LOCALAPPDATA%/ExpressAssistantServer/data/server.log and server-error.log; server.pid identifies the launcher. Credentials may appear in startup stdout; do not print it in tool output.
- Tests from server/: `uv run --frozen --with pytest pytest -q tests --basetemp C:\Users\Meta\AppData\Local\Temp\.agents\expressassistant-pytest`. 32 passed on ROG.
- Browser acceptance from Mac: 197 rows, 4 active cards, 3 accounts, interval30, detail opens, zero script errors; REST401 without auth and MCP initialize/tools-list passed.

## Durable Lessons

- On this ROG, port18765 raises WinError10048 even when no listener appears in the default TCP table. Port18769 was bind-tested and deployed instead; no other network service was changed.
- Edge156 exits1002 under the SYSTEM task; installed Chrome156 succeeds under the same account. Use `--browser chrome --login-mode remote`.
- Restrict data ACL on the root with inheritable Meta/SYSTEM grants, then reset child ACLs to inherit. Applying inheritance removal recursively left existing files unreadable; Store silently falls back to empty data on read errors, so verify actual data via Store after ACL changes.
- Stop-ScheduledTask alone left the Python child group alive in this deployment. Use runtime/Stop-Server.ps1 before restarting to keep one data writer.

## Docs Index

- docs/design/server.md: channel fetch, merge, login and data boundaries; historical Mac-only verification is superseded by the current facts above.
- server/README.md and server/AI_API.md: usage, REST/MCP semantics and phone maintenance API.
- server/runtime/deployment-acceptance.json: sanitized live migration evidence.
- docs/log/2026-11.md: deployment completion log.
