# Changelog

All notable changes to `botmaker-core`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

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
