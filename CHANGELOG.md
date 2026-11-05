# Changelog

All notable changes to `botmaker-core`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

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
