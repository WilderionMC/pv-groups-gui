---
name: pv-groups-gui-local-testing
description: Build PV Groups GUI, update its local Modrinth installations, and prepare or run its isolated desktop test server. Applies to this project, not other mods or servers.
---

# Local PV Groups GUI testing

Project: the repository containing this skill.

The user authorized updating this addon's client JAR in every compatible Modrinth installation after artifact builds, and its server bridge in a separate desktop test server. This authorization persists for future builds of this project. Do not ask again for these file updates.

Run `./gradlew.bat assembleArtifacts -PwithClient=true --no-daemon`. On this machine, `.local-test.json` enables the post-build installer `tools/sync_local_test.py`. It reads installed profiles from Modrinth's SQLite database in read-only mode, matches the active Minecraft version and loader against `client-targets.json`, verifies artifact hashes, backs up replaced files, and installs the matching client build. Preserve other mods and disabled states. Unsupported profiles must be reported, never given the wrong JAR. Managed Modrinth content must be updated through its supported app workflow; never overwrite shared content-store blobs or edit its live database directly.

Local paths are configured in `.local-test.json`, which is ignored by Git. Current paths:

- Modrinth: `%APPDATA%\ModrinthApp`; both discovered profiles currently use Fabric 26.2.
- Dedicated server: `%USERPROFILE%\Desktop\pv-groups-gui-test-server`, Paper 26.2, `127.0.0.1:25567`, voice UDP `24467`.
- Backups: `%LOCALAPPDATA%\PVGroupsGui\backups`.
- Existing `%USERPROFILE%\Desktop\server` is only the original source of the Paper binary, Plasmo Voice, Groups addon and previously accepted EULA. Do not deploy into it, start it, stop it, or copy its world and unrelated plugins.

Prepare and update the dedicated server after builds, but start it only when the user explicitly asks. For a server that must remain available after the chat turn, start Java as a detached process with `Start-Process -WindowStyle Hidden`, the server directory as its working directory, and stdout/stderr redirected to files. A tool-owned PTY previously exited after the turn and left the server offline; do not rely on it for persistence. Check the log for `Done`, Plasmo Voice and Groups initialization, and the GUI addon, then check a Minecraft server-list status response on port 25567 before reporting readiness. Save the PID and launch mode in `build/local-test-server-session.json`. If a test server is already running, report that its bridge update needs a restart and wait for authorization to stop/restart it. Running clients need a restart to load a changed JAR.

For manual testing, connect a matching client to `127.0.0.1:25567`, press G, and check browse/create/join/password/invite/leave, settings and owner actions with a second player. Check narrow and wide windows and different GUI scales, long names, many list entries, focus and text preservation during resize, and reconnection. Compare both client and server logs. A successful build or binary-reference check does not establish visual or gameplay correctness.

For automated UI checks, `:client-ui:runClientGameTest -PwithClient=true` runs Fabric Client GameTest on 26.2, including G through Minecraft input. Shared scenarios are in `client-ui/src/uiTest`; keep them out of distributable JARs. They use deterministic protocol replies rather than the Paper bridge.

The user chose the latest patch in each compatibility line, not only the newest 1.21.x or the newest release overall. `ui-test-matrix.json` selects 15 game versions and 31 supported loader/version pairs. Build `:client-ui:matrixTestAgent` with `-PwithClient=true`, then run `tools/run_ui_matrix.py` (development dependency: `minecraft-launcher-lib==8.0` in `build/matrix-deps`). It creates isolated clients under `build/ui-matrix`, using JDK 8/17/21/25. Results, logs and screenshots are separate for every pair. These runs exercise actual rendering and Plasmo event routing with offline fixtures; G is checked for registration, not physical input. Never describe 32 successful artifact/ABI checks as 32 game launches. Report actual runtime results and remaining failures from `build/ui-matrix/report.json`.

Minecraft 1.20.6 is excluded by the user's decision. The user subsequently requested removing the old UI modules without backups; do not restore them. Shared translations and the icon now live in `client-ui/src/main/resources`. The current 32-client matrix follows Plasmo Voice 2.1.17. Use JDK 25 for builds and the Paper 26.2 test server.
