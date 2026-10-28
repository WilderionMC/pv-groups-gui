# PV Groups GUI

A client interface for [pv-addon-groups](https://github.com/plasmoapp/pv-addon-groups).
Players can browse, create and join voice groups, invite friends and manage members.

The interface uses Plasmo Voice's own `GuiScreen`, `ScreenWrapper`, `Button`,
`TextFieldWidget` and `AbstractScrollbar` components. Current builds do not require owo-lib.
Forms and lists scroll within the panel, and the panel width adjusts to the game window.

## Supported versions

The client targets follow the published stable builds of **Plasmo Voice 2.1.17**.
Client dependencies are limited to `>=2.1.17 <2.2` because the interface uses internal
Plasmo Voice classes. Exact versions, download URLs and checksums are recorded in
[client-targets.json](client-targets.json).

| Minecraft | Fabric | Forge | NeoForge |
|-----------|--------|-------|----------|
| 1.16.5 | Yes | Yes | |
| 1.17.1 | Yes | Yes | |
| 1.18.2 | Yes | Yes | |
| 1.19–1.19.2 | Yes | Yes | |
| 1.19.3 | Yes | Yes | |
| 1.19.4 | Yes | Yes | |
| 1.20–1.20.1 | Yes | Yes | |
| 1.20.3–1.20.4 | Yes | Yes | |
| 1.21–1.21.1 | Yes | Yes | Yes |
| 1.21.4 | Yes | | Yes |
| 1.21.6–1.21.8 | Yes | | Yes |
| 1.21.11 | Yes | | Yes |
| 26.1–26.1.2 | Yes | | Yes |
| 26.2 | Yes | | Yes |
| 26.3 | Yes | | Yes |

The build produces 32 client JARs. For example, `fabric-1.21.6` covers Minecraft
1.21.6–1.21.8. NeoForge needs two artifacts for that range: `neoforge-1.21.6` and
`neoforge-1.21.7`.

Minecraft 1.20.6 is excluded from the current targets. Its last official Plasmo Voice
release is from the 2.0.10 line, which uses a different UI and API. The old client
modules and owo XML layouts have been removed. Shared translations and the icon live
in `client-ui/src/main/resources`.

## Installation

Install Plasmo Voice 2.1.17 and the client JAR for your Minecraft version and loader.
Also install the dependencies required by your chosen Plasmo Voice build.

On the server or proxy, install the universal `pv-addon-groups-gui-1.0.0.jar` alongside
Plasmo Voice and `pv-addon-groups`. If Groups runs on a proxy, install the bridge on
that proxy. The server JAR includes entry points for Paper/Folia, Velocity/BungeeCord
and the supported mod loaders.

## Usage

Press **G** to open the interface. Change the binding in **Plasmo Voice's** hotkey
settings. The addon uses the same hotkey API on every supported loader.

Players can create, join and leave groups, enter passwords, invite players, kick and
ban members, remove bans and transfer ownership. Group settings include the name,
password, persistence and permission filter. The interface enables actions according
to the permissions sent by the server. The server checks every request again.

Group actions have text labels. Settings, ownership transfer and deletion are under
**Manage group**. The creation form keeps entry permissions and persistence under
**Advanced**. All settings, including password removal and clearing entry permissions,
apply through **Save changes**. **Cancel** discards the draft. Settings requests run
in order. If a request fails, later changes are not sent; changes already confirmed
by the server remain applied.

Passwords are hidden by default. **Show** temporarily reveals the entered text.
Join errors appear beside the password field, while action results appear outside
the scrolling list. The selected player has a border and a label before an action.
The password dialog names the protected group. Group search filters names on the
current page, whose number stays visible. Confirmation dialogs name the action,
group or player and explain the effects of transferring ownership, deleting or banning.

The group browser uses compact cards with a title, member heads, player count and a
join button. Protected groups open a separate password dialog, leaving more room
for the group list. Joining an open group sends the request immediately.

The interface follows the Minecraft language setting. Translations include English,
Russian, Spanish, Brazilian Portuguese, French, German, Simplified Chinese, Japanese,
Korean and Hindi. Build checks verify that every translation has the required keys
and matching format arguments.

## Building and verification

Builds require JDK 25 and access to the dependency repositories. Shared client code
and the server bridge target Java 8 bytecode. At runtime, use the Java version
required by the corresponding Minecraft release.

Build all client targets, check Plasmo Voice compatibility and build the server JAR:

```powershell
.\gradlew.bat assembleArtifacts -PwithClient=true --no-daemon
```

The 33 installable JARs and `SHA256SUMS.txt` appear in `build/artifacts/plasmo-ui/`.
If `.local-test.json` exists, the command also updates this addon's JAR in compatible
local Modrinth profiles and the bridge in the separate test server. Replaced JARs
are backed up. Without that local configuration, the command only builds artifacts.
It does not publish them.

Build one client:

```powershell
.\gradlew.bat :client-ui:jar-fabric-26.3 -PwithClient=true --no-daemon
```

Verify all client targets:

```powershell
.\gradlew.bat :client-ui:check -PwithClient=true --no-daemon
```

Verification downloads the pinned official Plasmo Voice JARs and checks SHA-512.
`tools/VerifyClientCompatibility.java` checks referenced classes, methods and fields
for every client, Java 8 bytecode, loader metadata and the absence of owo dependencies
or direct references to Minecraft class names. It also checks reflective access to
the client executor and player head rendering. Binary compatibility checks do not
replace game launches and visual inspection.

Build only the server JAR:

```powershell
.\gradlew.bat :dist-server:shadowJar --no-daemon
```

## Local testing

Run the Fabric 26.2 client UI tests:

```powershell
.\gradlew.bat :client-ui:runClientGameTest -PwithClient=true --no-daemon
```

These tests require JDK 25 and a graphical environment. The first run downloads
Minecraft assets. Fabric Client GameTest creates a temporary world and exercises
the Plasmo screens through mouse and keyboard input. Protocol replies come from
test fixtures, so this suite does not verify the Paper bridge or voice audio.
It does not use installed Modrinth profiles or the separate desktop server.

Scenarios cover G input, creation, password entry, action permissions, member updates,
invitations, ownership transfer, moderation, deletion and leave confirmations,
scrolling and pagination. Additional checks cover text and focus during resize,
scroll position after refresh, resetting fields and missing font glyphs. Invitation
screens are reopened after successful invitations and member updates.

Screenshots are saved to `client-ui/build/run/clientGameTest/screenshots/` and the
log to `client-ui/build/run/clientGameTest/logs/latest.log`. Fabric clears the temporary
run directory on the next run. Screenshots are for review; the suite does not yet
compare them against reference images.

Code and dependencies from `src/gametest` are excluded from installable JARs.
The regular `:client-ui:check` task does not launch Minecraft. It checks target
compatibility, panel sizes and hotkey registration.

`ui-test-matrix.json` selects the latest patch in each supported compatibility line:
15 Minecraft versions and 31 version/loader combinations. The ten shared scenarios
in `client-ui/src/uiTest` are used by both Fabric Client GameTest and the standalone
runner for older clients.

```powershell
python -m pip install --target build/matrix-deps minecraft-launcher-lib==8.0
.\gradlew.bat :client-ui:matrixTestAgent assembleArtifacts -PwithClient=true -PsyncLocalTest=false --no-daemon
python tools/run_ui_matrix.py
```

The runner creates isolated installations in `build/ui-matrix`, downloads the game
and loaders and starts clients one at a time. It requires JDK 8, 17, 21 and 25,
located through the Gradle JDK cache and the local JDK 25 installation. Installed
Modrinth profiles and desktop servers are not used. Use `--list` to list targets
or `--targets fabric-26.2` to select one.

Standalone client runs test rendering and Plasmo mouse and keyboard event handlers.
They use protocol fixtures and default player skins. They check G registration;
physical G input and missing font glyphs are additionally covered by Fabric Client
GameTest 26.2. The report distinguishes these checks. The test agent and shared
scenarios are excluded from installable JARs.

Results are saved to `build/ui-matrix/report.json`. Logs and screenshots for each
combination are under `build/ui-matrix/runs/<loader-version>/`. A `prepared` status
means only that installation succeeded. A successful UI run has status `passed`
and ten completed scenarios.

### Real bridge tests

`tools/run_bridge_e2e.py` starts an isolated server and two Minecraft clients with
Plasmo Voice, Groups and the production bridge. Scenarios use actual server requests
and replies to test creation, joining, passwords, settings, cancellation, invitations,
permissions, kicking, banning, unbanning, ownership transfer, deletion and reconnection.
Both clients verify membership and ownership changes. A separate negative scenario
sends a settings request without owner permissions and checks the server's rejection.

### Verified bridge results

On **2026-10-28**, all **31 selected client combinations passed all 14 scenarios**,
**434 successful scenario runs** in total. Each combination used two real Minecraft
clients and one compatible server. Deployed bridge and client JARs were checked
against the production artifact SHA-256 hashes.

[Verification snapshot and artifact hashes](verification/bridge-2026-10-28.json)

| Minecraft | Client loader | Server and build | Result |
| --- | --- | --- | --- |
| 1.16.5 | Fabric | Paper 794 | 14/14 |
| 1.16.5 | Forge | Paper 794 | 14/14 |
| 1.17.1 | Fabric | Paper 411 | 14/14 |
| 1.17.1 | Forge | Paper 411 | 14/14 |
| 1.18.2 | Fabric | Paper 388 | 14/14 |
| 1.18.2 | Forge | Paper 388 | 14/14 |
| 1.19.2 | Fabric | Paper 307 | 14/14 |
| 1.19.2 | Forge | Paper 307 | 14/14 |
| 1.19.3 | Fabric | Fabric Loader 0.20.1 | 14/14 |
| 1.19.3 | Forge | Forge 44.1.23 | 14/14 |
| 1.19.4 | Fabric | Paper 550 | 14/14 |
| 1.19.4 | Forge | Paper 550 | 14/14 |
| 1.20.1 | Fabric | Paper 196 | 14/14 |
| 1.20.1 | Forge | Paper 196 | 14/14 |
| 1.20.4 | Fabric | Paper 499 | 14/14 |
| 1.20.4 | Forge | Paper 499 | 14/14 |
| 1.21.1 | Fabric | Paper 133 | 14/14 |
| 1.21.1 | Forge | Forge 52.1.2 | 14/14 |
| 1.21.1 | NeoForge | Paper 133 | 14/14 |
| 1.21.4 | Fabric | Paper 232 | 14/14 |
| 1.21.4 | NeoForge | NeoForge 21.4.161 | 14/14 |
| 1.21.8 | Fabric | Fabric Loader 0.20.1 | 14/14 |
| 1.21.8 | NeoForge | Paper 60 | 14/14 |
| 1.21.11 | Fabric | Velocity 4.3.1-45 → Paper 132 | 14/14 |
| 1.21.11 | NeoForge | Paper 132 | 14/14 |
| 26.1.2 | Fabric | BungeeCord 2107 → Paper 74 | 14/14 |
| 26.1.2 | NeoForge | Paper 74 | 14/14 |
| 26.2 | Fabric | Paper 135 | 14/14 |
| 26.2 | NeoForge | Paper 135 | 14/14 |
| 26.3 | Fabric | Folia 5 (ALPHA) | 14/14 |
| 26.3 | NeoForge | NeoForge 26.3.0.99 | 14/14 |

For Velocity and BungeeCord, Groups and the GUI bridge ran on the proxy; their
backends contained neither addon. Folia 26.3 was tested on an ALPHA build.
Paper 1.16.5 used Java 17 with `Paper.IgnoreJavaVersion=true` to load Plasmo Voice
2.1.17. The table records the tested server configuration for each client;
it does not establish compatibility between every possible client/server pair.

### Running bridge tests

```powershell
.\gradlew.bat :client-ui:matrixTestAgent -PwithClient=true "-PmatrixAgentName=bridge-e2e-agent.jar" --no-daemon
python tools/run_bridge_e2e.py --client-matrix --resume
```

The client matrix assigns one compatible server to each of the 31 selected client
combinations. It records the client loader and actual server platform in
`build/bridge-e2e/client-report.json` and `client-report.md`. It does not try every
client against every server platform.

To run one combination, use:

```powershell
python tools/run_bridge_e2e.py --platform paper --game 26.2 --client-loader fabric
```

The runner currently targets Windows and uses the client test dependencies and JDK installations. Spigot and
CraftBukkit additionally require the official BuildTools environment. The broader
server catalog is recorded in `build/bridge-e2e/matrix.json`. An available upstream
build is not evidence of a successful test.

Tests run under `build/bridge-e2e` on loopback ports 25670/25671 and 26670/26671.
They do not change installed Modrinth profiles or desktop servers. Only the two
test players are allowed to join. The runner reuses the EULA already accepted for
the separate test server and does not accept a new agreement. Proxy tests remove
Groups and the bridge from the backend so requests must reach the bridge on the proxy.

Per-combination logs and client state snapshots are under `build/bridge-e2e/runs`.
Check `status`, completed scenarios and `failureKind`. A download or setup error
is not a bridge test result. `--resume` skips successful runs for the current scenarios
and matching artifacts. Tests drive production GUI events; physical G input and
voice audio delivery are outside this suite.

### Manual testing

The separate Paper 26.2 test server lives at
`%USERPROFILE%\Desktop\pv-groups-gui-test-server`. Its game address is
`127.0.0.1:25567`, with voice UDP on port `24467`. The existing `Desktop\server`
is not changed. Start the test server explicitly with its `start.ps1` script.
Building does not start or restart it.

Connect with a matching Modrinth profile and press G. With a second player, check
browsing, creation, joining, passwords, invitations, leaving, settings and owner
actions. Resize the window and change the GUI scale. Check long names, scrolling
and preservation of entered text.

Replacing a JAR takes effect after restarting the client or server. If a running
Minecraft process locks the file, the installer leaves it untouched and reports
the pending update. A later build retries installation. The report is saved to
`build/local-test-sync.json`, with backups under `%LOCALAPPDATA%\PVGroupsGui\backups`.

## Project structure

| Module | Purpose |
|--------|---------|
| `protocol` | Binary GUI protocol and packet registration |
| `bridge` | Shared server logic and adapter for `pv-addon-groups` |
| `paper` | Server addon |
| `velocity` | Proxy addon |
| `dist-server` | Universal server JAR |
| `client` | State controller and network client |
| `client-ui` | Shared Plasmo Voice UI, loader entry points and client JARs |

The client sends requests through Plasmo Voice TCP packet `0x64`. On a proxy, the
bridge prevents forwarding that packet to the backend. The client receives `Hello`,
`Browse`, `ActionResult` and `State`. Packet registration uses the internal
`PacketTcpCodec` registry.

The bridge compiles against `pv-addon-groups` 1.1.1. Permission checks use its
registered `CommandHandler` rather than a separate list of permission strings.
