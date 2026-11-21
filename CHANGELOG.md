# Changelog

All notable changes to `botmaker-core`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

- Security advisories (catalog trust, phase 5): a plugin release or a run copy a catalog advisory names is refused
  until the user says *Run anyway* for that release alone, kept in `advisory-overrides.json` of the config folder;
  the check reads the last verified catalog, so it holds offline. Pickers leave affected releases out, each pulled
  with the advisory as its reason (`Advisories`, `CatalogIndex.advisories`). A release several advisories name is
  asked about each, worst first (phase 6's review): a *Run anyway* lets one through, not all, and a severity this
  build does not know ranks below `low` (`Advisories.WORST_LAST`).
- The catalog is signed (catalog trust, phase 4): `CatalogClient` reads `catalog.signed.json` and uses only a catalog
  whose Ed25519 signature `CatalogSignature` verifies against `catalog-keys.txt`. It keeps only a verified catalog on
  disk, as the signed file. A catalog that fails is treated like no answer: the kept one serves, `Reply.trust()`
  `UNVERIFIED`. A verified catalog built before the kept one never replaces it. The unsigned `catalog.json` kept before
  is deleted. `Reply` holds `Trust` (`VERIFIED`, `CACHED`, `UNVERIFIED`) in place of `fresh`, which stays as a method.
- A plugin's together (`History.together`, 2026-11-21): `HostPluginValues.queued` queues every write a plugin asks on
  the thread instead of making it, and `performAll` makes the queue as one step, or none of it. At the first refused
  write, `project.FileJournal` puts back every file the together touched (open buffer and disk), deletes a class it
  created, and the step records only what stayed, inside a held batch too. A client sends the whole queue as one
  `Request.Together` (`CoreClient.together`); `OpenProject` redraws the open file once at its end. `NestedClasses.namedIn`
  parses an unchanged holder once across a run of reads.

- A catalog row is found by a slug its repository had before a rename or a transfer (catalog trust, phase 3):
  `GalleryEntry` and `PluginRegistry.Plugin` carry `formerly` and `answersTo`, `CatalogIndex.botAt`,
  `GitHubGallery.find`, `PluginRegistry.entryFor` (by the old coordinate), a run copy's `CatalogCopies.copyOf` and
  `BmkOpen` (a `.bmk` naming the old slug) match by it; a row listed at the slug wins over another's former one.
  Plugins & Libraries offers no version for a project on the old coordinate.
- A yanked release (catalog trust, phase 2) is offered by no picker. A row's `tag` and `version` are its newest
  release not yanked, and are blank when every release was pulled. The row is still listed. `approvedTags()` skips
  yanked releases, and the home plugins never bind one. `GalleryEntry` and `PluginRegistry.Plugin` gain
  `yanked(tag)` and `pulledLine(installed)`, which give "v3 was pulled: <reason>. Newest: v2". `GalleryEntry` also
  gains `allPulled()`. Nothing is refused: when every release of a side-run bot was pulled, the copy kept before
  still serves.
- New methods read catalog schema 6: `GalleryEntry.downloads()`, `updatedAt()`, `runsHere(Os)`, `systemsLine()`
  and `factsLine(Instant)`, plus `PluginRegistry.Plugin.updatedAt()` and `CatalogIndex.updatedAgo`. A cached
  schema-5 catalog still reads; it has no counts or times, and every system is allowed.
- `PublishRequest.platforms` carries the systems the Publish sheet ticked. `StudioPublishHost.commitFiles` saves
  the pom as a *Published* version before the tag.

### Changed

- A batch of setup writes on one set of classes finds the set's holder once. The first write finds it the old way,
  which parses the whole bot with bindings. Each later write checks on that one file that the class is still there and
  still marked, then writes. The old lookup was two thirds of each write in the Screens map's ⋯ ▸ *Tidy* (63 writes).
  A write that creates a class forgets what was found. `NestedClasses.setupSetIn` is the one-file write.
- Reading a class's setup (`PluginValues.open` on a set of classes) checks first the one file its holder was last found
  in (`NestedClasses.namedIn`), and asks the whole bot only when the holder is no longer there, marked or a set. Each
  write changes the bot, so every read between a run of writes built the whole-bot index again.
- A value is matched against the bot's constants by the last lookup while the constants are equal and the grammar the
  same. A lookup spells each constant once, and a new one for each write spelled every constant again. With both
  changes, *Tidy* of 63 cards went from 10.4 s to 5.8 s.

## [0.3.0] — 2026-11-21

### Fixed

- A plugin's values of this PC with no class yet upgrade quietly: the check that they still compile no longer hands
  javac an empty list, which it refused with "no source files", warning that they did not compile.
- A bot that names no plugin keeps its run log and its wire to Studio again. Since run claims (2026-11-15) the run's
  inner half (`AgentRun`) failed to link without the contract on the bot's classpath, and every such run printed
  `this run keeps no log: java.lang.NoClassDefFoundError: …Claims$Service`. The call that serves claims now sits in
  `RunClaims`, which loads only for one of several runs.
- A plugin's dev reload (`StudioServices.devReload`) asked from a client's copy of the plugin, as the SDK's
  `explore reload_sdk` is while Studio is attached, is no longer refused as *not here*: the client forwards it
  (`DevReloads.installForwarding`, `Request.DevReload`) and the core's own copy reloads, with the same checks.
- A method value of a generic type is written with its arguments (`ManagedMethod.form()`): `List<Launcher>
  launchTargets()`, not a raw `List`, which the value grammar cannot read, so the Game window left it "written by
  hand" and a website could not be made the launch target. Bind repairs a raw one it finds, the bot's other code
  left as it was, in every bot created since 2026-11-04.

### Added

- The core starts a plugin's run companions (`RunEntries.Companions`): when a run of a declared `RunEntry` starts,
  each companion that serves it starts once, however many clients are attached, and stops when the run ends or the
  project closes. A bot's own run, a try or a debug run starts none.
- `StudioServices.devReload`, served for the open project in dev mode (`plugin.DevReloads`): a plugin restarts its
  own run on its rebuilt jar. Its jar is loaded first on a loader of its own, over only what the last bind admitted
  (`PluginHost.admitted()`), so no plugin the gate refused is constructed; a jar that does not construct, or no
  longer declares the run, is refused with the loader's words and nothing is stopped. Then the run is stopped (30 s
  at most), the plugins are bound again as *Reload plugins* binds them, and the run is started with no arguments.
- `operations`, served with or without Studio: every bound plugin's assistant tools (with the MCP name each is
  called by) and declared runs, each with its parameters' JSON schema, as one JSON answer, with the open project's
  folder. `botmaker tool` reads it, so its keys are read by the cli (`assist.Operations`).
- `run_bot` with `entry` takes `args`, a JSON object of what the plugin's run takes (`RunEntry.takes`). They are
  checked before the start, a refusal naming the field and listing what the run takes, and reach the run as one
  `-Dbotmaker.run.arguments` property; a refused or unknown entry lists each entry's parameters. An entry with a
  required parameter stays off the bar, and `Runs.start` refuses it. `Request.RunEntry` carries the arguments.

### Changed

- Every file states its license and copyright by REUSE (`REUSE.toml`, `LICENSES/`), every Java source starts with
  its SPDX header, and the `reuse` workflow checks both on each push.
- Licensed under PolyForm Noncommercial 1.0.0, copyright LiQiyeDev: free for any noncommercial use, commercial use
  needs a license from LiQiyeDev. Releases up to v0.2.1 stay CC0 1.0.
- Built against botmaker-sdk 1.12.0 (was 1.11.0).
- Built against botmaker-shared 0.10.0 (was 0.9.0).
- Built against botmaker-session 0.9.0 (was 0.8.0).
- Built against botmaker-studio-api 0.19.0 (was 0.18.0).
- Built against botmaker-plugin-host 0.14.0 (was 0.13.0).
- Built against botmaker-cli 0.14.0 (was 0.13.1).
- Built against botmaker-runlog 0.2.0 (was 0.1.0).
- Built against botmaker-java 0.4.0 (was 0.3.0).

## [0.2.1] — 2026-11-16

### Fixed

- A tag's release job no longer fails: it adds its files to the release page sluice wrote instead of refusing it.

### Changed

- Built against botmaker-cli 0.13.1 (was 0.13.0).

## [0.2.0] — 2026-11-16

### Added

- **The live runs of this PC as the Runs window shows them** (2026-11-16): `run.claims.LiveRunsView` reads the
  registry and groups its runs by bot (my bot, run copy, side run). Each run has its picks by pooled parameter and
  the last line it printed. It starts and stops nothing; any process may read it.
- **The bot's pools written from the Parameters window** (2026-11-16):
  - `project.params.PoolEdits` puts or removes one pool's method in an `@Pools` group, through JDT, and gives its
    Java line.
  - `JavaPools` writes both copies: `params/bot/<Group>Pools.java` with this PC's elements, and its starting copy
    with the same chain listing none.

- **A dry run, all or none, and pins for several runs** (2026-11-16): `run_bot {runs}` takes `dry_run` (says how
  many can run and what each would be dealt, "7 of 11 can run: …", holding and starting nothing), `all_or_none`
  (starts none unless all can) and `pins` (`pool=value` or `run:pool=value`, the pool and value as a dry run's picks
  write them). `Request.RunSeveral` carries a `run.claims.SeveralAsk` in place of its count; a count's
  `RunsStarted` lists each row that could run, with no run id.
- **Several runs from the MCP** (2026-11-16): `run_bot {runs: k}` starts k runs dealt by the bot's pools and answers
  what started, each run's id and picks, the warnings and what a plugin offers to cover a shortfall (listed, never
  run); `run_state`, `stop` and `read_trace` take a `run_id`. On the channel, `Request.RunSeveral` and
  `CoreEvent.RunsStarted`. The core stays up while several runs go.
- **A run keeps its plugins' values of this PC** (2026-11-15): before a run, each served plugin's method values are
  seeded (`HostValues.readyForRun`) and the run's plan names the folder (`RunPlan.hostValues`), which the agent serves
  through plugin-host's `RunHostFolders`; when the run ends, what it wrote is one commit, `Run of <bot>` (none when
  nothing changed), for a single run and for each of several.

- **What several runs claim is said to be decided while running** (2026-11-15, `run.claims.ClaimCalls`): a start of
  several runs finds the methods of the compiled bot that call `Claims.claim`, and its words end with "decided while
  running: the keys claimed in …", since the host can neither count nor deal those keys ahead
  (`PooledRuns.Outcome.claims`). Two live runs claiming one key, and a key a crashed run claimed, are tested over real
  run JVMs.
- **A plugin's setup work holds a pooled value** (2026-11-15, `run.claims.RegistryHolds`): the contract's
  `liveRuns()` holds the value in the registry of live runs under this process, as the plugin's pool's element, so a
  dealing finds it held; refused with the holding run's words. `HostServices.serveLiveRuns` installs it, in the core
  and in Studio; a parameter no bound plugin's pool fills holds nothing.
- **Several runs are dealt the plugins' pools too** (2026-11-15, `run.claims.BoundMethods`): `startSeveral` takes each
  loaded plugin's pools, binds each to the bot's group method carrying its parameter's mark (a conditional parameter
  only while it applies), and deals them before the bot's own.
- **Several runs of one bot started at once** (2026-11-15, `run.CodeExecutionService.startSeveral`,
  `run.claims.PooledRuns`): one compile, then k rows dealt from the bot's `@Pools` groups and held in the live runs
  under one lock, each run started with its row and run id, and its row freed when its process ends or at once when
  it never starts. Every pooled method is checked against the compile first, and one that is gone, changed its type
  or has no body refuses the start in words. Each run's output goes to `target/botmaker-several/<run id>.log`. While
  they go, neither a Run nor another start compiles over their classes, and no run takes the project's background
  session.
- **The run agent gives each run its dealt values** (2026-11-15, `run.agent.PooledMethods`, `PooledValues`,
  `WrittenValues`): only the listed methods are rewritten as their classes load, to return the value built once in
  the bot's JVM, so a bot reading its parameter by a direct call reads its own row. The agent builds each pool again
  and stops the run (exit 78, `RunAgent.STOPPED`) when it lists other elements than the core dealt from, or a value
  cannot be built; a stopped run ends as its main class initialises, before any of the bot's code (`MainStop`; an exit
  from `premain` takes 60 s on JDK 26 on Windows). A run of several serves `Claims` over the live runs (`RunClaims`)
  and stops when it cannot.
- **A pool's element written one way** (2026-11-15, `run.claims.Canonical`): every class fully qualified from the
  value's imports and each literal by its value (`1_000` and `1000` alike), the identity of an element in the live
  runs, `dealt.json` and a pin, and what the agent builds.
- **The live runs of this PC** (2026-11-15, `run.claims.RunRegistry`): one file per run under
  `UserDirs.data()/live-runs/`, with its bot, run id, the elements its row holds, its run-time claims and its process.
  Every check and write is under one lock shared by every process, a run whose process is gone is dropped as it is
  read, and a plugin pool's element is held for every bot on this PC.
- **Dealing several runs** (2026-11-15, `run.claims.Dealer`): each run asked for is dealt a row, one pick from every
  pool, the plugins' first then the bot's (`PoolSources`), going back on a dead end. It counts `take` and `each`,
  deals a pin as given, and keeps a row on what it held last unless its pool rotates (`DealHistory`, the bot's
  `.botmaker/runs/dealt.json`). The capacity is the rows found, with why there are fewer in words: too few elements,
  elements with nothing to go with, elements another run holds. A plugin that throws fails the dealing by name.
- **The bot's pools listed** (2026-11-15, `project.params.JavaParameters.pools`): every pool of its `@Pools` groups, read
  by botmaker-java's `PoolReader`. An element may be one of the bot's own records.
- **A bot run beside the open one** (2026-11-15, `run.SideRunner`): the contract's `sideRuns()`, served in the core
  and in Studio (`HostServices.serveSideRuns`). It resolves the folder's pom, compiles it with javac only when its
  sources or classpath changed (`botmaker-side-run.stamp`), filling each private parameter the folder lacks from
  `starting-params/`, and starts it with the run agent, its output in `botmaker-side-run.log`. A process still running
  when the host exits is killed.
- **A catalog bot's run copy for a plugin** (2026-11-15, `sharing.CatalogCopies`, the contract's
  `SideRuns.catalogCopy`): got as *Get* gets one (`BmkDownload`, then `BmkOpen.openGot`) into the same bots folder,
  for an official bot only. The listed tag's checked copy serves without a download; a newer listed tag is got and
  replaces it; only with GitHub out of reach does the newest copy kept serve. A bot the catalog, reached, no longer
  lists is not run, and a file that isn't the one it pinned (`BmkDownload.NotPinned`, new) is refused, never swapped
  for the old copy. One copy is got at a time, and the catalog is asked again on each, so a release since BotMaker
  started is got.

- **The `device` tool refuses another instance on the address** (2026-11-14): it connects through shared's
  `AdbDevice.connect(EmulatorInstance)`, which checks the instance's ID file.

- **`StudioProjectSettings.WorkspaceLayout.runChoice`** (2026-11-13): what Studio's Run split button last ran, kept
  in `settings.json` beside the rest of the window's layout; `withPublishDivider` and `withOpenFiles` keep it.

### Changed

- **A plugin's values of this PC read faster** (2026-11-14): `HostValues` parses a holder class again only when its
  text changed. Each value opened used to parse every class, so a window that opens several (the Emulators window's
  Accounts tab opens eight) took a second or more. A write by another process is still seen at once.
- **Words that named Studio's old tabs** (2026-11-13): a refused update, an upgrade's summary, a `.bmk` export refused
  for want of a commit and the `checkpoint` tool's description now say the Versions window. The `hidden_trace`
  setting says the Run tab's Trace.
- Built against botmaker-sdk 1.11.0 (was 1.10.1).
- Built against botmaker-shared 0.9.0 (was 0.8.1).
- Built against botmaker-session 0.8.0 (was 0.7.1).
- Built against botmaker-studio-api 0.18.0 (was 0.17.1).
- Built against botmaker-plugin-host 0.13.0 (was 0.12.1).
- Built against botmaker-cli 0.13.0 (was 0.12.1).
- Built against botmaker-java 0.3.0 (was 0.2.1).

## [0.1.1] — 2026-11-13

### Added

- **A plugin's folder of this PC is never opened as a project** (`plugin.HostFolders.ownedByAPlugin`, 2026-11-13):
  `Projects.opened`, the one way a project opens (`open_project`, Studio's argument, the last project reopened),
  refuses a folder under `UserDirs.config()/plugins/`, saying the plugin's own windows show and change it; real paths
  are compared, so a short name or a link is refused too. `ProjectPreferences` never records one as a recent or the
  last project, and forgets one it remembered.
- **A repair of this PC's values that fails serves them read-only** (`plugin.HostValues`, 2026-11-13): what it
  rewrote is put back, nothing is recorded or committed, and each write or undo is refused with the repair's reason
  until the plugin is loaded again. The commit "Before the upgrade to <version>" holds the values as the older
  version left them. The folder's repository gets `* -text` in `.git/info/attributes`, unversioned, so a restore on a
  PC with `core.autocrlf` puts back LF, not CRLF.
- **Undo of this PC's values** (`plugin.HostFolderHistory`, `StudioServices.hostHistory()`, 2026-11-13): ↶ puts the
  tree of the commit before the current step back as a new commit, "Undo Set GREETING", and ↷ does the step again
  the same way, so nothing is rewritten. Steps go down to the newest upgrade or first commit, never past it. A write
  made since an undo drops what could be redone. A batch commits its writes once, under its own label.
  `ProjectVcs.restoreTo` takes the new commit's origin and label.

- `device` gets `accounts`: the Google accounts signed in on a running instance, by email (the user's call,
  2026-11-12), read only. While BotMaker signs an instance in to Google or out of it, every action that shows,
  touches or changes it is refused (`screenshot`, `shell`, `logcat`, the taps, the app and install actions, `stop`,
  `push`, `pull`; shared's `SignInsRunning`): a password is typed there. Its reads stay open. Nothing in the tool
  signs in, applies a configuration or manages instances.
- `plugin.HostValues`: a plugin's values of this PC (`HostHolder`) kept as Java in
  `<config>/plugins/<group>/<artifact>/`, read and written as a bot's are. The folder is a project the plugin manages
  (`plugin.HostProject`): a `pom.xml` naming the plugin at the version that last wrote, the classes in
  `src/main/java/<group>/<artifact>/`, and its own git, in which each accepted write is one commit labelled with the
  value (`Set GREETING`). An older folder (classes at its root, `.history/`, `host.properties`) is moved into that
  layout once, committed as "Moved from the folder of this PC". Each write is compiled against the plugin's classpath
  and refused with javac's sentence when it would not compile, committing nothing; the pom's version refuses a write
  from a version older than the last writer (a `-SNAPSHOT` is older than its release). Method values only so far. A
  write locks the folder across processes (`.lock`).
- `StudioServices.hostValues()` is served, with or without a bot open (`plugin.HostFolders`): the newest version of a
  plugin bound in this process serves its values of this PC, and a project pinning an older one gets that copy.
- A newer plugin version repairs its host folder before its first read or write (`plugin.HostUpgrade`), as a bot's
  values are on that plugin's upgrade: its `@Migrate` rules when the older jar is on this PC, then a gone id removed and
  a value that no longer compiles reset and marked `@Refactor`. What it replaces is committed first ("Before the
  upgrade to …") and the repair is one commit ("Upgraded to …"), the version recorded only once it succeeded.
- A bot's values never hold a value of this PC: a bind plans no host holder, and `pluginValues()` neither reads,
  opens nor creates one.
- `device`'s `focus` names the home app and marks an app in front that is the emulator's own (its launcher, store or
  ad), and reads the resumed activity when no window has the focus.
- `device` no longer lists or starts GameLoop (2026-11-12, dropped with shared's support for it).
- `device`'s `checks` and `fix` cover BlueStacks, LDPlayer, MuMu and MEmu (shared's
  `WindowsEmulatorChecks`); `fix` with `undo` puts back the last Fix of the check it names.
- `CoreClient.watchCalls()` asks for every MCP call (`Request.WatchCalls`), as Studio's Assistant window does.
- Every MCP call the core serves is told to each client that asks (`Request.WatchCalls`), with or without a project
  open: the `CoreEvent`s `ToolCallStarted` (tool, plugin, arguments, when) and `ToolCallEnded` (OK, refused or error,
  the reply, how long), each image in the reply as a JPEG thumbnail at most 960 px across. The last 500 calls are kept
  in memory (the images of the last 50), and a client that asks hears them first. The calls are told on a thread of
  their own, so a slow client never holds up a tool's answer. A plugin's tool names its plugin in its `_meta`
  (`botmaker/plugin`).
- What a call did to a device is told under it: `ToolCallStep` (the device, the ADB command, frame grab, install, push
  or window input, whether it worked, what came back, how long) and `ToolCallProgress` (an install's upload, in bytes,
  each second). `ToolCallEnded.elsewhere` counts the steps that ran meanwhile on a thread without any call's scope.
  The first 200 steps of a call are kept for a client that asks later.
- `ToolCallEnded.extras` says what a call did that its reply's text does not: the points a `device` tap or swipe
  touched (`botmaker/tap` in the reply's `_meta`) and the methods an `edit_code` changed, by file, class and method
  (`botmaker/changed`). An image part carries the image's own width and height, so a point drawn on its thumbnail
  lands where it was touched.
- The MCP tool `device`, served by the core with or without Studio and a project open (with Studio attached too
  since 2026-11-11, when the Assistant window's session was found to have none): list the emulators on this computer,
  start and stop one, read its checks and run their fixes, list its apps, search Google Play for a game's package,
  install one (a file, or its Play page), launch, stop and restart it, read what is in front, take a screenshot, tap,
  swipe, press a key, and run a read-only adb shell command. Each action names its instance as `list` does.
- `device` covers raw adb too: `logcat` (the last lines, of one running app, from a level up, holding a text), `processes` (pid,
  memory, name, filtered by name), `crash` (an app's main process, by its pid, to test what follows), `push` (a file
  from this computer, only under `/sdcard`, `/storage` or `/data/local/tmp`, never a hidden file or BotMaker's
  settings) and `pull` (into BotMaker's `pulls` cache folder only, numbered past a file already there). A push's and
  a pull's bytes go out as the call's `ToolCallProgress`.
- A bot's plugins' MCP tools with no Studio: a project opened through the MCP serves its plugins' tools from the core,
  by the names Studio gives them (`botmaker_setup`, …), until Studio attaches and serves its own. A tool that needs
  Studio's screen answers that it does, with `start_studio`.
- Plugin MCP tools with no bot open: the core binds every plugin the catalog lists from an admin or trusted publisher,
  at its newest release this contract serves, checks each jar and pom against the catalog's sha256 (a jar that differs
  is left out), and serves their tools until a project opens; they come back when it closes. A tool that needs a bot
  says to `open_project`. A core run from a checkout takes a plugin's local `-SNAPSHOT` build first. `core_status`
  lists what was bound and left out.
- `core_status` (the core's version and process, whether Studio is attached, the open project, a running bot, and the
  jars rebuilt since it started) and `quit_core` (ends the core so the next call starts a fresh one; refused while a
  bot runs, and while Studio is attached unless `force`), served whether Studio is attached or not.

### Changed

- The MCP server's instructions speak of a BotMaker bot, Studio open or not, so a client the core serves alone
  (`botmaker ai`, `mcp-bridge`) is not told it works in Studio.
- `McpConfig` writes Studio's endpoint as the cli's `AiTool.Connection.Http`.
- Built against botmaker-sdk 1.10.1 (was 1.10.0).
- Built against botmaker-shared 0.8.1 (was 0.8.0).
- Built against botmaker-session 0.7.1 (was 0.7.0).
- Built against botmaker-studio-api 0.17.1 (was 0.17.0).
- Built against botmaker-plugin-host 0.12.1 (was 0.12.0).
- Built against botmaker-cli 0.12.1 (was 0.12.0).
- Built against botmaker-java 0.2.1 (was 0.2.0).

### Removed

- `assist.AiTool` and `assist.BotGuard`: they are the cli's now (`com.botmaker.cli.ai`), with a stdio connection beside
  the endpoint, a guard in a bot's folder for every client, and `botmaker ai` to start one without Studio.

### Fixed

- Opening a project in a core whose jars were rebuilt under it (`NoClassDefFoundError`) answered nothing, so Studio
  loaded forever; the open is now refused with why, the project's lock released, and its plugins unbound.
- A plugin's parameter group committed in `plugins/` while `params/` has its own (an older Studio's move) was never
  offered back, since `params/` held the file already: `HolderMove` now plans removing the copy, `params/` kept as
  it is and every use of the copy following it, and the banner says it is a committed copy.

### Changed

- Built against botmaker-cli 0.12.1, whose catalog check fills a bot's `params/` from `starting-params/` before it
  compiles the tag.

## [0.1.0] — 2026-11-10

### Added

- A plugin's window starts one of its declared runs (`Runs.start(RunEntry)`, 2026-11-09): `HostRuns` asks the host's
  new `Requests.startEntry`, which the core runs as `Request.RunEntry`, and refuses with a status line while a run goes
  or when no loaded plugin declares the entry. `RunEntries.Declared` carries `onBar`.

### Changed

- The run's trace rewrites the classes a reload compiles beside the bot's class folder too
  (`target/botmaker-explore/gen-<n>`, `TracePlan.isBotClass`, 2026-11-09), so a bot reloaded by a run exploring on
  its own keeps its call trace.
- `LockResolver` locks a whole class that carries an annotation marked `@Param.WrittenByRun` (the SDK's `@Facts`), with
  the reason "Facts are written by the run." (`RunWritten`, 2026-11-09), and the file declaring one is
  `Managed.FACTS`, badge `Facts - Read Only`. The annotation is read by its binding, or resolved through the unit's
  imports and package (a single-type import shadowing the rest, a nested name tried with `$`) and loaded from the
  plugins' loader; a file's verdict is kept until it changes.
- A tool's refusal that shows an image (save_digits' cut boxes) is sent as a result whose text starts `REFUSED:`,
  not as an MCP error: Claude Code shows only an error's text, so the image never reached the assistant (2026-11-09).
- Built against botmaker-sdk 1.10.0.
- Built against botmaker-shared 0.8.0.
- Built against botmaker-session 0.7.0.
- Built against botmaker-studio-api 0.17.0.
- Built against botmaker-plugin-host 0.12.0.
- Built against botmaker-cli 0.12.0.
- Built against botmaker-runlog 0.1.0.
- Built against botmaker-java 0.2.0.

### Removed

- `SessionEnvironment.x11InstallCommand`: nothing installs an X11 session now (Studio's `WaylandNotice`, 2026-11-09).

### Added

- `Request.Restore` is Review's Undo, asked of the core (2026-11-09). `parser.refactor.FunctionRestore` finds the
  newest version without the refactor's mark and writes the function back from it, with the imports it names. It
  refuses with a sentence when it cannot. The write is one step of the core's history. The open file's new text comes
  as `MethodsChanged`, and another file comes as `SourcesChanged`. `CoreClient.restore` sends it.
- `ManagedMigrations.anyMethod(BotIndex)` says whether any method is annotated `@Migrate`, read from the trees.
- `PluginUpgradeService.rebuiltRepair()` plans the repair a rebuilt dev plugin needs under the same `-SNAPSHOT`
  (2026-11-09): gone ids removed, values that no longer compile reset and marked `@Refactor`. It returns a `Rebuilt`
  with the plan and what could not be checked. `applyRepair` writes it, and `pinnedJar` resolves the pinned jar.
- `ManagedRepairs` retypes a marked method whose return type no longer resolves to the type the plugin declares now:
  a type that moved package (the SDK's `Run`, `api.flow` → `api.bot`). It writes the simple name and re-points the
  import, or the whole name when the file imports another type of that name. A generic type is not retyped. An
  unresolved import that only a removed value used goes with it, and the repair says so. An unused import the author
  left stays.

### Changed

- `BotSources`' read-only walks (`firstMatch`, `scan`, `index`) reuse their last read of the source tree while no file
  or directory under it has changed, checked by one stat each (2026-11-09). A read made within 100 ms of a file's
  modification time is not kept, since the file system's clock is coarse. Before, every value a plugin opened walked
  and read the whole tree again.
- `ProjectConfig` finds the entry's package, and whether a file holds `main`, through botmaker-java's `BotEntry`
  (2026-11-09). It no longer uses a regex prefilter of its own.
- `StarterSources.blank` builds the blank bot's class as a tree and prints it through `SourceFormatter` (2026-11-09).
  The formatter leaves no blank line between `public class MyBot {` and `main`.

### Fixed

- A statement dropped on the line after a comment (insert, paste, a host's statement, a move, a lifted value) goes
  through the rewrite like any other (2026-11-09). It was the node's debug print, spliced in as text at the comment's
  end, which ignored the formatter and could fail to parse. `CodeEditor`'s `PendingInsert` is gone.
- Taking a plugin's mark off a value the bot still names removes the annotation with `ASTRewrite.remove`
  (2026-11-09), not by cutting its characters out of the file (`ManagedRepairs.unmarked`).

- A plugin's old parameter fields are repaired on every bind (2026-11-05, params-cleanup phase 1), not only on an
  upgrade. A field whose id the group in `params/` also marks is removed, or kept unmarked while the bot still names it.
  A field of what is now a method value (`launchTargets`) becomes that method. A dev-mode reload of the same
  `-SNAPSHOT` runs no upgrade, so the SDK's `@Param` fields used to stay beside their group
  (`ManagedRepairs.leftoverFields`).
- A plugin's parameter found in another group than its holder's moves there on bind (2026-11-05, params-cleanup
  phase 2). The method moves whole, comments and value included, in `params/` and in its starting copy. The target
  group is written when missing, and a group pair the move empties is deleted. A move is skipped, with a warning,
  when the starting copy lacks the method, the target has a method of that name, or the bot would stop compiling
  (`MisplacedParams`).

### Added

- `JavaParameters.newGroup` writes the bot's own empty group, `params/bot/<Name>.java`, and its starting copy, and
  refuses a name Java does not accept or one the bot already has (2026-11-05, params-cleanup phase 5).
  `JavaParameters.ownGroups` lists the bot's groups by their files, an empty one too. `JavaParameters.declaration`
  answers where a parameter is declared, as a use, for the window's *Open in code*.

### Changed

- A parameter's category is the class that holds it (2026-11-05, params-cleanup phase 4): the reader sets it to the
  group's simple name, and an edit that changes it is refused. The upgrade and every bind strip `category = …` from
  `@Param` and `@Param.Shown`. A method row's description falls back to its group's. `JavaParameters.add` takes the
  new parameter's `Param.Changes`; `categories(…)` is gone.
- Types compared as types (2026-11-05, clean-java phase 10): a signature edit asks `TypeNodes.writes` whether the file
  already names the type. A type written `Map<String,  Point>` stays as written when the same type is picked, and a
  class that only shares a simple name, or the raw form of a generic, is written. `LockResolver` locks `main` however
  its `String` array is spelled.
- Reading through the tree (2026-11-05, clean-java phase 9): `ProjectConfig` reads `package` and `main` from a parse, so
  a comment or a string saying them no longer makes a folder a bot or moves its package; a `main` counts only with
  `String[]` or no parameters, where the text match took any. `PluginHolders` finds the lines naming a removed plugin's
  files on the tree, so a trailing comment or a string is no reference. A file that does not parse is read by its tokens
  for both questions, and every such file is reported on deletion.
- Comments stay comments (2026-11-05, clean-java phase 8): a comment block, a holder's doc comment and the ▶ Try
  class's header all write through `CommentText`, so what is typed cannot end the comment. `*/` in a block comment, a lone
  carriage return and a unicode escape used to be refused as an edit that broke the code, or broke the compile. A
  comment with code after it on its line stays a block comment: editing `/* note */ int b = 2;` used to write
  `// renamed int b = 2;`, and the statement was gone.
- The `@Migrate` engine substitutes trees (2026-11-05, clean-java phase 7): a rule's body is parsed once per plan and
  filled through an `ASTRewrite`, each part keeping the bot's spelling and comments; parentheses come from
  `Precedence` on the tree, so `twice(inc(x))` becomes `(x + 1) * 2`, not `x + 1 * 2`. A managed value's rule substitutes
  copies of its parts. A seeded rule is built with `ast.new*`, and `migrate write` parses the author's body and refuses
  one that is not one expression or statement; a comment inside that body is not kept.
- New files and moved imports are trees (2026-11-05, clean-java phase 6): `ClassKind.source`, `BotMethods`' new
  class and `Seed.skeleton` build their unit through `ManagedHolders.unit`, which writes no package for the default
  one; `HolderMove` renames and adds imports through an `ASTRewrite`, not text offsets. An edit whose rewrite cannot
  be applied (`AstRewriteHelper.RewriteFailed`) is told as a status line and publishes nothing, where it used to
  publish the unchanged source.
- A parameter's value crosses as a node (2026-11-05, clean-java phase 5): `ParameterRow.value()` is the
  `JavaValue` the file was read into, `valueText()` what a cell shows, and two rows are equal when their values
  match as trees. Nothing parses a row's value again to write it. A migration step's parameter name is a name node.

### Removed

- `RunnerValues` and the `<data>/runner-values/` copy of a runner's values (2026-11-05): `params/` is the only store.
  An update keeps it (`UnpackedBots.moveValues`, and git for a merged tag), and nothing reads the old files.
  Removing a run copy now forgets its values.

### Added

- `read_trace` follows a run by cursor (2026-11-05, plan phase 12): given `from` (the `next` of an earlier answer, or
  -1 for now), it answers with the whole lines since then. Its first line says `next=… starts=… ends=… busy=…
  skipped=…` (`RunLog.since`). `botmaker bot run` streams a run with it.
- The MCP without Studio (2026-11-05, plan phase 11). With no client attached the core serves `list_projects`,
  `open_project` and the run tools (`run_bot`, `stop`, `run_state`, `read_trace`, `reports`) itself (`CoreHome`), beside
  its edit tools. A project the MCP opened stays open until a client takes it. A run its client kept going on leaving
  (`Request.Close(keepRun)`) keeps the project open, and the core up, until it ends; a client that opens the project
  meanwhile hears `RunState(RUNNING)` and the pid. `CoreLauncher` saves the command it started a core with as
  `core-command.json`, which `botmaker mcp-bridge` runs when no core answers.
- Phase 10d (2026-11-05), one way in.
  - The core serves the MCP for every client. Its own edit tools run on the open project's thread (`OpenProject.bot()`).
    A client registers its tools over its channel (`Request.Tools`), and the core forwards each call (`Reply.Call`).
    The client answers with `Request.Called`. Before each of its own tools runs, the core asks the client with a screen
    (`ADMIT`). `ServedTools` keeps the list, and a client's tools go when its channel ends. The core's own
    tools are served only while a client with a screen admits them, so a Studio that stops serving stops them too; a
    change removes and adds only the tools that changed.
  - `CoreLink` keeps a client tied to the core. When the core stops, the link starts a new one and `Rejoin` reopens the
    project and the open file. A client hears `stopped` and `restarted`, or `lost` after three failed tries.
  - The open-time passes (`ProjectOpenMigrations`) run in the core before it reads the sources.
    `CoreEvent.Opened` tells a client what they did.
  - The core binds the MCP port saved in `mcp.json`, and a free port when that one is taken.
  - `TypeCatalog` moved here from Studio.
- Phase 10c (2026-11-05), plugins through the core.
  - A client's plugin writes the bot through the core. Each write is a `PluginWrite` in `Request.PluginWriting`.
    `Request.Batch` opens and ends a batch, and what a batch writes is one step of the core's history.
    `HostPluginValues.installForwarding` sends a copy's writes on, and `perform` makes a forwarded write.
  - The core resolves the pom alone: `OpenProject.Libraries.resolve` returns a `MavenService.Resolution` and does the
    contract's `reconcile`. The core writes missing holders on first attach and after `Reload`. It tells
    `CoreEvent.LibrariesResolved` as a client attaches and before a reload's answer.
  - `Request.Try` carries a `run.trial.TrialAsk`: a statement by `BlockId` with its given values, or a method run on its
    own. The core plans it and writes the caller. `ProbeCalls` moved here from Studio.
  - The core's plugins have their own `HostRuns` and `HostHistory`.

### Removed

- Phase 10d (2026-11-05): `Request.Commit` and `Request.Rewritten`, `CoreEditor.commit(file, original, source, label)`,
  `HomeEndpoint`, and `McpEndpoint.start`. Java text no longer crosses from a client to the core, and the core's server
  is the only MCP.
- `Request.Recorded` and `CoreClient.recorded`, since a plugin's writes are the core's. `CoreEvent.UiRequest`, never
  sent: the core forwards no window, and its `hasUi()` is false.

- Phase 10b (2026-11-05), runs in the core. `OpenProject` owns `CodeExecutionService` and `DebuggingService`. New
  requests: `Compile`, `Run`, `RunEntry`, `Try`, `Debug`, and the `Request.Control`s `Stop`, `Answer`, `Debugging`, which
  run on a thread of their own so a stop never waits behind a slow ask. New events: `RunTraced`, `RunFrame`, `RunProcess`,
  `DebugSession`, `DebugPaused`, `DebugFollowed`, and `SourcesChanged`, which names files beside the open one that
  changed on disk without their text, so a parameter's value stays off the channel. `params/` is fitted when the project
  opens. Closing a project ends its bot, and so does the last client leaving. `HostRuns` takes the pid as a supplier.
  `CodeExecutionService` no longer holds a UI thread or merges a run's parameter values into a client's copies: disk
  holds them and its `sourcesWritten` has the owner read them back.
- Phase 10a1 (2026-11-05), the core's side of Studio's cut-over. `OpenProject.open` makes the open-time writes (the
  remotes' names, the contract's dependency, a plugin's editor libraries). Two requests, `Rewritten` and `Recorded`,
  carry a client's migration and a plugin's writes; a recorded text becomes the core's. `Reload` resolves changed
  libraries again. The sources and settings are read from disk before each request, so a write made outside the core is
  never overwritten, and a new text of the open file is told as `MethodsChanged`. A refused show leaves no file open. A
  pom that does not resolve still opens. Sources are read without following links. `ProjectState.setSavedByCore` stops a
  client's snapshot writing sources, and a run's kept values go to disk at once. `CoreLauncher.process` passes the module
  path and the sandbox, and a core that dies as it starts fails the connect at once.
- `server` (2026-11-05): the core as its own process, not yet used by Studio. `CoreMain` serves on a free port of
  `127.0.0.1` behind the MCP's token and hello, says where in `core.json` in the cache directory, and ends after ten
  idle minutes or on a quit while nothing uses it. `CoreServer` serves the MCP, `/core` (what it is), `/core/quit` and
  the clients' channels at `/studio`, a stream of JSON lines one way and a post per message the other (`Channel`,
  `ChannelClient`), opened only for a client of the core's version. `CoreLauncher` finds the running core or starts
  one, one start at a time under a lock beside `core.json`, asks an older unused core to quit and refuses a client
  while an older core is in use, naming both versions. `ProjectLock` holds an open project for one process; a dead
  owner's lock is taken. `McpEndpoint.guard` puts a server behind the token.
- A project open in the core's process (2026-11-05), not yet used by Studio. A channel carries `Request`s (open, show a
  file, apply, a user's edit, an undo or redo, an assistant's commit, close), each answered, and `Reply.Told` gives every
  attached client each change. `OpenProject` holds the project, its lock and its plugins, runs every ask on one thread
  in arrival order and saves each accepted change of the open file as it lands; `Projects` keeps one project per core
  and closes it when its last client leaves. `CoreClient` is the client's side. `CoreEditor.commit` takes a session's
  texts too.

### Changed

- The hello proof signs `<port>:<nonce>`, the port the client asked on, so a proof cannot be relayed from another
  port. `ProjectLock` refuses a linked `.botmaker` and never writes through a link. A channel the core ended is sent no
  `DELETE`.
- `assist` (2026-11-05): the MCP endpoint and its tools moved from Studio — `McpEndpoint`, `McpTools`, `ToolGroup`, the
  file, project, run and Studio tools, `RunLog`, `PastRuns`, `HomeEndpoint` with `HomeTools`, `AssistTurn`,
  `AssistTools`, `BlockView`, `StudioDriver`, `LiveBot`, `McpConfig`, `AiTool` and `BotGuard`. The MCP SDK and Jetty
  are this module's dependencies now. `ToolGroup.driven` and `homeTools` say whether Claude drives a client's screen
  and what the home serves of it, so `HomeEndpoint` names no client.
- `parser.StatementKind` and `parser.StatementBodies` (2026-11-05): what a statement is as the editor draws it, and which
  bodies it holds; Studio's canvas and the assistant's `read_tree` both read the code through them. `parser.CallOwner`
  moved from Studio with them. `edit.DeclarableTypes`: the types a variable may be declared as, which a client offers.
- `plugin.UiPort` (2026-11-05): the look and choosers a plugin's dialogs reach, which a client with a screen fills;
  `UiPort.NONE` answers `Theme.PLAIN` and `Dialogs.NONE`, a host with no UI.
- The plugin host classes, moved from Studio (2026-11-05): `HostServices`, `HostRuns`, `HostHistory` (told the history's
  changes by `told`), `HostPluginValues`, `HostValueContext`, `HostTypes`, `HostParameters`, `HostParamScope`,
  `HostActionContext`, `HostOverlayContext`, `ConstantValues`, `EntryPoints`, `ToolCalls` and `WriteSteps` in `plugin`;
  plugin upgrade in `services.upgrade`; `services.JitPackSearch`.
- Sharing, moved from Studio (2026-11-05): the rest of `sharing` (publishing, the `.bmk` export and download, bot links
  and installs, the gallery, the plugin catalog, new projects' choices) and `services.LocalBuilds`. Also
  `project.ClassKind` (was Studio's `ClassFiles.Kind`) and `run.RunEntries`, a plugin's declared runs.

- Compile, run, try and debug, moved from Studio (2026-11-05): `com.botmaker.core.run` (`CodeExecutionService`,
  `DebuggingService`, `BotJvm`, `TreeCompile`, `WarmTries`, `ConsoleBatcher`, `RunTelemetry`, `RunCheck`, the run agent
  in `agent`, the try host in `tryhost`), `run.trial`, `run.debug`, and `run.capture` (`Capture`, `DesktopGrab`,
  `SessionEnvironment`). A run's check answers a `RunCheck.Verdict`, and the run tells its problems as
  `CoreEvent.Diagnostics`, which gains the severity `INFO`. `ProjectState.activeFileName` names the open file as the
  protocol does.
- Run events (2026-11-05): `CoreEvent.RunLogged` (a run that wrote a log, and its folder), `InputAsked` (the bot's
  question) and `Status` (the status line), and `runtime.RunFolders`, a project's run folders newest first.
- The project's undo history (2026-11-05): `CoreEditor.step(HistoryStep)` undoes and redoes whoever made the step,
  `recordRewrite` records files a plugin already wrote, and a new `CoreEvent.HistoryChanged` says what the Undo menu
  shows. `MethodsChanged` now carries the file's whole new text and whether its new names are the editor's guesses,
  which is what a client redraws from; an undo announces what it put back the same way.
- `CanvasEdit` (2026-11-05): every gesture the canvas can make, as a typed record naming its nodes by `BlockId`, each
  with a JSON round trip, run by `CoreEditor.userEdit`. A gesture may carry Java the user typed, so an assistant's
  request carrying one is refused.
- The module (2026-11-05), with the protocol a client and the core speak: `com.botmaker.core.edit.Edit`, the edits a
  client asks for (insert, set a slot, move, delete, rename) in an `EditRequest`, and `HistoryStep` (undo, redo, the
  project's), answered by an
  `EditResult` (accepted with the methods it changed, or refused with the reason); `com.botmaker.core.event.CoreEvent`,
  what the core tells its clients (methods changed, diagnostics, run state and output, a request for a window, a
  refusal). `CoreJson` reads and writes every one as JSON. `CoreLayersTest` keeps the module free of UI and Studio.
- The open project and the plugin host, moved from Studio (2026-11-05), each in the package it had there under
  `com.botmaker.core`: `project` (`ProjectState`, `ProjectConfig`, `ProjectCreator`, `ProjectRepair`,
  `ProjectOpenMigrations`, `LockResolver`, `PomInfo`, `UserLibrary`, `PluginFiles`, `ProjectManager`, the project's
  settings and preferences, `migration`), `plugin` (`PluginHost`, `PluginGate`, `PluginOwners`, the released and
  duplicate plugins, `PluginLogs`), `services.MavenService` and `ContractDependency`, `config`, `events.EventBus`,
  and what those need: parts of `project.managed`, `project.params` and `project.vcs`, `sharing`'s catalog client and
  unpacked bots, `runtime.JavacRelease`, `parser.refactor.ReviewMarker`. Studio calls them in-process. JavaFX is a
  `provided` dependency only for compiling against the contract's slot editors. A `tests` jar carries `TestPlugins`,
  `TestValues`, `TestValue`, `TestOutcome`, `MigrationTypes` and `TestScreen` for Studio's tests.
- The edit engine, moved from Studio (2026-11-05), each in the package it had there: `parser` (`CodeEditor`, which
  reports through the new `EditorOutput`; `NodeCreator`, `SlotVacancy`, `factories`, `handlers`, `guard`, `refactor`),
  `state` (undo), `project.managed`, `project.params`, `project.vcs.BlockDiff`, `migrate`, `index`,
  `nav.LibrarySource`, `validation`'s `DiagnosticLevel` and `ErrorTranslator`, `plugin`'s `PaletteCuration` and
  `ValueWire`, `services.EditorCompanions`. ClassGraph and LSP4J come with them.
- `edit.CoreEditor` (2026-11-05): an `EditRequest` applied by `BlockId` in the tree, with no drawn block, as one undo step
  or not at all, answered with the methods it changed and told to clients as `MethodsChanged`. `EditRequest.Guard` says
  whether a new compile error refuses an edit. `EditSession`, `EditStage` and `EditWorkspace` came from Studio's
  assistant, as did `Outcome` and `PaletteEntry`; `validation.SourceChecks` (unfilled slots, deprecated calls) and
  `nav.SourceNavigation` came from Studio too, neither knowing a block.

### Security

- A remote's fetch, tag listing, clone, attach and join give the user's GitHub token to GitHub only, as push already
  did (2026-11-05); a remote on any other host used to receive it.
