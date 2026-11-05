# CLAUDE.md

Guidance for working in **botmaker-core**, the headless process that owns an open bot.

Read the umbrella `../CLAUDE.md` first, and `../docs/superpowers/specs/2026-11-04-java-and-core-design.md` for why
this module exists.

## What this module is

The one owner of an open project: it opens it, edits its Java (through `botmaker-java`), compiles and runs it, loads
its plugins and serves the MCP. Studio is a client that draws what the core says and sends what the user does; the
assistant is another. Until the plan's phase 10 Studio calls it in-process; from then on it is its own process, one
per user.

- `edit`: what a client asks. `Edit` is a sealed set of records, the shape `edit_code` takes: `Insert`, `SetSlot`,
  `Move`, `Delete`, `Rename`. An `EditRequest` names the file (below the source root) and its edits in order; an
  `EditResult` is `Accepted` with each `ChangedMethod`, or `Refused` with its reason. A node is addressed by its
  `BlockId` path, as a string. Undo and redo are a `HistoryStep`, sent alone: the history is the project's, a
  request one file's.
- `event`: what the core tells every client. `CoreEvent` is sealed: `MethodsChanged` (the methods whose source or
  id changed, and the ids removed), `Diagnostics`, `RunState`, `RunOutput`, `RunLogged`, `InputAsked`, `Status`,
  `UiRequest`, `Refusal`.
- `CoreJson`: the one mapper every protocol record is read and written with, strict on reading (a missing component
  or trailing text is refused); phase 10's channel carries exactly this.
- The rest came from Studio (phase 6a, 2026-11-05), each in the package it had there: `project` (the open project,
  its config, creation, repair, open-time migrations, settings), `plugin` (`PluginHost` and its gate), `services`
  (`MavenService`), `config`, `events` (`EventBus`), and the parts of `project.managed`, `project.params`,
  `project.vcs`, `sharing`, `runtime` and `parser.refactor` those need. Studio's `/botmaker/host.properties` is read
  by `config.AppVersion` and `HostContract` from the classpath: in-process it is Studio's; the core's own process
  (phase 10) needs its own.
- The edit engine came too (phase 6b, 2026-11-05): `parser` (`CodeEditor`, its handlers, factories and guard),
  `state`, `project.managed`, `project.params`, `migrate`, `index`, part of `validation` and `nav`. `CodeEditor` reports
  through `parser.EditorOutput`, which Studio's `BusEditorOutput` turns into its events.
- `edit.CoreEditor` (phase 6c, 2026-11-05) is the one way a typed edit reaches the open bot: it finds its target by
  `BlockId` in the tree. A request runs as an `EditSession`, over `EditStage`s it edits with `CodeEditor`: whole or not
  at all, one write. `EditRequest.Guard` says how far the compiler judges it: `EVERY_EDIT` for an assistant,
  `EDITOR_RULES` for what the user did by hand. A client's own session lands through `commit`. `Edit.Rename` renames a
  local only. `MethodsChanged` carries methods, not a file's imports or fields. `validation.SourceChecks` says what a
  tree lacks, `nav.SourceNavigation` where a thing is; neither knows a block.
- `edit.CanvasEdit` (phase 6d, 2026-11-05) is what the user did on the canvas, one record per gesture, each naming its
  nodes by `BlockId`. `CoreEditor.userEdit` runs one through `CanvasEditor` on one live `CodeEditor`, whose locks, guard,
  words and history labels it keeps, and goes alone under `EDITOR_RULES`; under `EVERY_EDIT` it is refused, so an
  assistant's `EditRequest` never carries one. Studio still sends two for an assistant's tool, Java the host built
  itself: the MCP `rename`'s refactor and a plugin tool's call in the overlay, as they ran before; phase 9 moves them. `EditPayloads` is how its `ResolvedType`, `JavaValue`, `BotType.Choice` and
  reflected `Type` cross as JSON.
- Since phase 6e (2026-11-05) a client redraws from `MethodsChanged`, which carries the file's whole new text beside the
  methods it reshaped (the user's call), and the project's history is the core's: `CoreEditor.step(HistoryStep)` undoes
  or redoes, `recordRewrite` records what a plugin already wrote, `rewritten` records an open-time migration, and
  `HistoryChanged` says what the Undo menu shows. An undo announces what it put back as `MethodsChanged` and records
  nothing. `EditorOutput.written` is the editor's own report; Studio draws nothing from it.
- JavaFX is `provided` for one reason: `PluginHost` binds a plugin's slot editors, whose methods return a `Node`.
  Construct no Node here; phase 8's UI port removes it.
- The `tests` jar (`TestPlugins`, `TestValues`, `TestValue`, `TestOutcome`, `MigrationTypes`, `TestScreen`) is what Studio's tests
  load plugins and values with: keep those public. `parser.EditorFixture` is the core's own: a `CodeEditor` over a source
  string with no block drawn; `TestSources` gives the classpath and source root a test parses against.

## Rules

- Imports nothing above it: no `javafx`, no `com.botmaker.studio`, no SDK (`CoreLayersTest`).
- Edits cross as typed commands; Java text crosses only core → client, as source to draw. A slot's value is what
  its editor gives, read by the slot's type, never Java the core splices. The one exception is the user's own hands: a
  `CanvasEdit` may carry Java the user typed, pasted or restored, and only under `EDITOR_RULES`.
- A protocol record that changes is a protocol change: its JSON round trip is in `EditJsonTest` or
  `CoreEventJsonTest`, and a new subtype is registered in its `@JsonSubTypes`. A `CanvasEdit` is registered by `CoreJson`
  from its permitted classes, and its round trip is in `CanvasEditJsonTest`.
- Write Java with JDT trees, never strings; no plugin vocabulary.
- `botmaker-studio-api` is a plain dependency here: the core is a host, like Studio.
