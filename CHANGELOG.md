# Changelog

All notable changes to `botmaker-core`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

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
