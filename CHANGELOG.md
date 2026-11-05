# Changelog

All notable changes to `botmaker-core`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

- The module (2026-11-05), with the protocol a client and the core speak: `com.botmaker.core.edit.Edit`, the edits a
  client asks for (insert, set a slot, move, delete, rename) in an `EditRequest`, and `HistoryStep` (undo, redo, the
  project's), answered by an
  `EditResult` (accepted with the methods it changed, or refused with the reason); `com.botmaker.core.event.CoreEvent`,
  what the core tells its clients (methods changed, diagnostics, run state and output, a request for a window, a
  refusal). `CoreJson` reads and writes every one as JSON. `CoreLayersTest` keeps the module free of UI and Studio.
