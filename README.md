# botmaker-core

The headless BotMaker process that owns an open bot: it opens the project, edits its Java, compiles and runs it,
and serves the MCP. Studio is one client of it and the assistant another. Edits arrive as typed commands
(`com.botmaker.core.edit.Edit`) and changes leave as events (`com.botmaker.core.event.CoreEvent`).

It also serves **Remote Pilot** (`com.botmaker.core.pilot`): watch and drive this computer's runs from a phone or a
browser with [BotPilot](https://github.com/BotMakerDev/botmaker-pilot), started from Studio or with
`botmaker core pilot`.

```xml
<dependency>
    <groupId>com.github.BotMakerDev</groupId>
    <artifactId>botmaker-core</artifactId>
    <version>TAG</version>
</dependency>
```
