# ROADMAP — botmaker-core

## Open

- **A `@Migrate` rule does not reach `starting-params/`.** `Migration` rewrites what is below the source root, and the
  fitter then gives `params/` the starting copy's annotations back, so a deprecated type marking a parameter group
  (the SDK's `@GameState`) returns on the next compile, and breaks when the plugin's next release deletes it. The
  rewrite should cover the starting copy too.

- **The open project, editing, runs, plugins, history and the MCP move here from Studio**, then the core becomes its own
  process (`../docs/superpowers/plans/2026-11-04-java-and-core.md`, phases 6–12).
