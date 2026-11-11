package com.botmaker.core.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstall;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorLiveness;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.PlayStoreSearch;
import com.botmaker.shared.emulator.checks.CheckAnswers;
import com.botmaker.shared.emulator.checks.CheckResult;
import com.botmaker.shared.emulator.checks.EmulatorChecks;
import com.botmaker.shared.emulator.checks.Fixes;
import com.botmaker.shared.launch.EmulatorAppLauncher;
import com.botmaker.shared.tools.UserDirs;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code device}: the Android emulators and phones on this computer, with no project open (2026-11-10). It is the
 * core's own, built on shared's emulator layer alone, so it serves with no Studio and no bot: list the instances,
 * start and stop one, read its checks and run their fixes, find and install a game, start and stop it, look at the
 * screen and touch it, read its log and processes, crash an app to test what follows, copy files either way
 * (2026-11-11; a copy's bytes go out as the call's progress). A bot's {@code setup} and {@code launch_game} act on the bot's own target; this one names
 * its instance each call, as {@code list} gives it.
 */
final class DeviceTools {

    private static final String INSTANCE = "the instance, as list names it";
    private static final String PACKAGE = "the app's package, like com.example.game; search_store finds it";
    /** What {@code checks} is called in this tool, which a fix's answer points to. */
    private static final String CHECKS_CALL = "device's checks";

    /** {@code device}'s actions. */
    sealed interface Device {
        @Describe("the emulators on this computer: each product's status, then each instance with its state")
        record List() implements Device {
        }

        @Describe("start the instance, if it is stopped, and wait until Android has booted (minutes on a cold start)")
        record Start(@Describe(INSTANCE) String instance) implements Device {
        }

        @Describe("stop the instance by its product's own command")
        record Stop(@Describe(INSTANCE) String instance) implements Device {
        }

        @Describe("the instance's setup checks, a line each: ok, and whether fix repairs it")
        record Checks(@Describe(INSTANCE) String instance) implements Device {
        }

        @Describe("run the fix of one failing check, or undo its last fix; it runs on in the background, and checks "
                + "says how it went")
        record Fix(@Describe(INSTANCE) String instance, @Describe("its id in checks") String check,
                   @Describe(value = "true to put back what its last fix replaced", optional = true) Boolean undo)
                implements Device {
        }

        @Describe("the packages installed on the instance")
        record Apps(@Describe(INSTANCE) String instance) implements Device {
        }

        @Describe("search Google Play for a game: each hit's name and package")
        record SearchStore(@Describe("the game's name, or its Play address") String query) implements Device {
        }

        @Describe("install a game: from an .apk/.xapk file on this computer, or else by opening its Play page on the "
                + "instance, where the user presses Install")
        record Install(@Describe(INSTANCE) String instance,
                       @Describe(value = PACKAGE + "; for the Play page", optional = true) String pkg,
                       @Describe(value = "an .apk or .xapk on this computer, absolute", optional = true) String file)
                implements Device {
        }

        @Describe("start the app, bringing the instance up first, and wait until it is in front")
        record LaunchApp(@Describe(INSTANCE) String instance, @Describe(PACKAGE) String pkg) implements Device {
        }

        @Describe("force-stop the app")
        record StopApp(@Describe(INSTANCE) String instance, @Describe(PACKAGE) String pkg) implements Device {
        }

        @Describe("force-stop the app and start it again")
        record RestartApp(@Describe(INSTANCE) String instance, @Describe(PACKAGE) String pkg) implements Device {
        }

        @Describe("what is in front: the focused app's package, the resumed activity, and any system dialog")
        record Focus(@Describe(INSTANCE) String instance) implements Device {
        }

        @Describe("the instance's screen, at its own size")
        record Screenshot(@Describe(INSTANCE) String instance) implements Device {
        }

        @Describe("tap at x,y in the screen's pixels")
        record Tap(@Describe(INSTANCE) String instance, @Describe("px") int x, @Describe("px") int y)
                implements Device {
        }

        @Describe("swipe from x1,y1 to x2,y2 in the screen's pixels")
        record Swipe(@Describe(INSTANCE) String instance, @Describe("px") int x1, @Describe("px") int y1,
                     @Describe("px") int x2, @Describe("px") int y2,
                     @Describe(value = "how long, 300 ms when not given", optional = true) Integer ms)
                implements Device {
        }

        @Describe("press an Android key by its keycode: 3 home, 4 back, 187 recent apps")
        record Key(@Describe(INSTANCE) String instance, @Describe("the keycode") int code) implements Device {
        }

        @Describe("run a read-only adb shell command: dumpsys, getprop, pm list, wm size, wm density, pidof, "
                + "settings get; no other command, and none of ; & | < > ` $ or a line break")
        record Shell(@Describe(INSTANCE) String instance, @Describe("the command") String command)
                implements Device {
        }

        @Describe("the log's last lines, oldest first: all of it, or one running app's")
        record Logcat(@Describe(INSTANCE) String instance,
                      @Describe(value = PACKAGE + "; only its lines while it runs", optional = true) String pkg,
                      @Describe(value = "the lowest level shown: V, D, I, W, E or F", optional = true) String level,
                      @Describe(value = "only lines holding this text, any case", optional = true) String text,
                      @Describe(value = "how many lines, " + LOG_LINES + " when not given, at most " + LOG_MOST,
                              optional = true) Integer lines)
                implements Device {
        }

        @Describe("the running processes, a line each: pid, memory in kB, name")
        record Processes(@Describe(INSTANCE) String instance,
                         @Describe(value = "only those whose name holds this, like a package", optional = true)
                         String name)
                implements Device {
        }

        @Describe("crash the running app's main process, as a test of what follows: Android's crash dialog, a bot's "
                + "recovery; focus and processes say what it did")
        record Crash(@Describe(INSTANCE) String instance, @Describe(PACKAGE) String pkg) implements Device {
        }

        @Describe("copy a file from this computer to the instance, its folders made; to " + WRITABLE)
        record Push(@Describe(INSTANCE) String instance,
                    @Describe("the file on this computer, absolute; not a hidden one, nor BotMaker's settings")
                    String file,
                    @Describe("where on the instance, absolute") String to)
                implements Device {
        }

        @Describe("copy a file from the instance into BotMaker's pulls folder on this computer, under a name no other "
                + "file there has; the answer says where")
        record Pull(@Describe(INSTANCE) String instance, @Describe("the file on the instance, absolute") String path)
                implements Device {
        }
    }

    private static final int LOG_LINES = 200;
    private static final int LOG_MOST = 2000;
    private static final String WRITABLE = "/sdcard, /storage or /data/local/tmp";
    /** The folders {@code push} writes under: shared storage and adb's own scratch, nothing an app or Android runs. */
    static final List<String> PUSH_ROOTS = List.of("/sdcard/", "/storage/", "/data/local/tmp/");
    /** A device path {@code push} and {@code pull} take: absolute, plain characters, no {@code ..}. */
    private static final Pattern DEVICE_PATH = Pattern.compile("/[\\w./+@-]*");
    private static final Pattern LOG_LEVEL = Pattern.compile("[VDIWEF]");

    /** The commands {@code shell} runs, each a word or two that only read. */
    static final List<String> SHELL_READS = List.of("dumpsys", "getprop", "pm list", "wm size",
            "wm density", "pidof", "settings get");
    /** The reads that take no argument: given one, {@code wm size 720x1280} sets what it would read. */
    private static final List<String> BARE_READS = List.of("wm size", "wm density");
    /** Words that make a dumpsys service act rather than report ({@code dumpsys battery set level 5}). */
    private static final Pattern DUMPSYS_ACTS = Pattern.compile(
            "\\s(set|reset|unplug|enable|disable|clear|put|delete|kill|force-idle|unforce|step|disable-temp)(\\s|$)");
    private static final Pattern SHELL_SPECIAL = Pattern.compile("[;&|<>`$\\r\\n\\\\]");

    private DeviceTools() {}

    /** The {@code device} tool. */
    static AssistantTool<AssistantTool.OneOf<Device>> tool() {
        return AssistantTool.named("device").describedAs("The Android emulators and phones on this computer, with "
                + "or without a project: list, start, stop, checks, fix, apps, search_store, install, launch_app, "
                + "stop_app, restart_app, focus, screenshot, tap, swipe, key, shell, logcat, processes, crash, push, "
                + "pull. Name an instance as list does.")
                .takesOneOf(Device.class).handledBy((action, context) -> reply(action));
    }

    private static AgentReply reply(Device action) {
        try {
            if (action instanceof Device.Screenshot shot) {
                return withDevice(shot.instance(), device -> {
                    BufferedImage image = device.screencap();
                    return AgentReply.text(image.getWidth() + "×" + image.getHeight()).and(AgentReply.image(image));
                });
            }
            return AgentReply.text(said(action));
        } catch (IllegalArgumentException e) {
            return AgentReply.refused("REFUSED: " + e.getMessage());
        } catch (RuntimeException e) {
            return AgentReply.refused("device could not do that: " + e.getMessage());
        }
    }

    /** Every action that answers a sentence; one that cannot is an {@link IllegalArgumentException}. */
    private static String said(Device action) {
        return switch (action) {
            case Device.List ignored -> listed(Platforms.discoverDetailed());
            case Device.SearchStore search -> searched(search.query());
            case Device.Screenshot ignored -> throw new IllegalStateException("reply answers it with its image");
            case Device.Start start -> {
                EmulatorInstance instance = instance(start.instance());
                Optional<EmulatorInstance> up = EmulatorReadiness.bringUp(instance, null);
                yield up.isPresent() ? instance.name() + " is up; Android has booted, adb at "
                        + up.get().endpoint() + "." : EmulatorReadiness.notReady(instance);
            }
            case Device.Stop stop -> {
                EmulatorInstance instance = instance(stop.instance());
                if (!instance.canStop()) {
                    throw new IllegalArgumentException("BotMaker has no stop command for " + instance.brand()
                            + " yet; close its window.");
                }
                yield EmulatorLauncher.stop(instance) ? instance.name() + " is stopping."
                        : instance.name() + "'s stop command did not run.";
            }
            case Device.Checks checks -> {
                EmulatorInstance instance = instance(checks.instance());
                Fixes.Context context = fixes(instance);
                yield CheckAnswers.answer(EmulatorChecks.run(instance, null),
                        result -> Fixes.of(result, context).isPresent(), CheckAnswers.runnerOf(instance));
            }
            case Device.Fix fix -> {
                EmulatorInstance instance = instance(fix.instance());
                Fixes.Context context = fixes(instance);
                List<CheckResult> results = EmulatorChecks.run(instance, null);
                CheckAnswers.Said said = CheckAnswers.fix(fix.check(), Boolean.TRUE.equals(fix.undo()), results,
                        result -> Fixes.of(result, context), Fixes::undo, CheckAnswers.runnerOf(instance),
                        CHECKS_CALL);
                if (said.refused()) throw new IllegalArgumentException(said.text());
                yield said.text();
            }
            case Device.Apps apps -> withDevice(apps.instance(), device -> {
                List<String> packages = device.installedApps().stream().sorted().toList();
                return packages.isEmpty() ? "No app is installed." : String.join("\n", packages);
            });
            case Device.Install install -> installed(install);
            case Device.LaunchApp launch -> outcome(
                    EmulatorAppLauncher.start(pkg(launch.pkg()), instance(launch.instance()).reference()));
            case Device.StopApp stop -> {
                EmulatorInstance instance = instance(stop.instance());
                boolean emulatorStopped = EmulatorAppLauncher.stop(pkg(stop.pkg()), instance.reference());
                yield stop.pkg() + " is force-stopped" + (emulatorStopped
                        ? "; a process of it outlived that, so the emulator was stopped too." : ".");
            }
            case Device.RestartApp restart -> outcome(
                    EmulatorAppLauncher.restart(pkg(restart.pkg()), instance(restart.instance()).reference()));
            case Device.Focus focus -> withDevice(focus.instance(), device -> {
                List<String> lines = new ArrayList<>();
                lines.add("focused: " + device.focusedPackage().orElse("(none)"));
                String current = device.currentApp();
                lines.add("resumed: " + (current == null || current.isBlank() ? "(none)" : current.strip()));
                device.systemDialog().ifPresent(shown -> lines.add("dialog: " + shown.dialog().said()
                        + (shown.theApps() ? " (about the app in front)" : "")));
                return String.join("\n", lines);
            });
            case Device.Tap tap -> withDevice(tap.instance(), device -> {
                device.tap(tap.x(), tap.y());
                ReplyMeta.put(ReplyMeta.TAP, ReplyMeta.point(tap.x(), tap.y()));
                return "Tapped " + tap.x() + "," + tap.y() + ".";
            });
            case Device.Swipe swipe -> withDevice(swipe.instance(), device -> {
                long ms = swipe.ms() == null ? 300 : Math.clamp(swipe.ms(), 1, 10_000);
                device.swipe(swipe.x1(), swipe.y1(), swipe.x2(), swipe.y2(), ms);
                ReplyMeta.put(ReplyMeta.TAP, ReplyMeta.path(List.of(ReplyMeta.point(swipe.x1(), swipe.y1()),
                        ReplyMeta.point(swipe.x2(), swipe.y2()))));
                return "Swiped " + swipe.x1() + "," + swipe.y1() + " → " + swipe.x2() + "," + swipe.y2() + " in "
                        + ms + " ms.";
            });
            case Device.Key key -> {
                if (key.code() < 0 || key.code() > 400) {
                    throw new IllegalArgumentException(key.code() + " is no Android keycode.");
                }
                yield withDevice(key.instance(), device -> {
                    device.key(key.code());
                    return "Pressed key " + key.code() + ".";
                });
            }
            case Device.Shell shell -> {
                String command = readOnly(shell.command());
                yield withDevice(shell.instance(), device -> {
                    String out = device.shell(command);
                    return out == null || out.isBlank() ? "(no output)" : out.strip();
                });
            }
            case Device.Logcat logcat -> {
                String level = logLevel(logcat.level());
                int lines = logcat.lines() == null ? LOG_LINES : Math.clamp(logcat.lines(), 1, LOG_MOST);
                String pkg = logcat.pkg() == null || logcat.pkg().isBlank() ? null : pkg(logcat.pkg());
                String text = logcat.text() == null || logcat.text().isBlank() ? null : logcat.text().strip();
                yield withDevice(logcat.instance(), device -> {
                    String pid = pkg == null ? null : mainPid(device, pkg);
                    String out = text == null ? device.shell(logcatCommand(lines, pid, level))
                            : lastHolding(device.shell(logcatCommand(0, pid, level)), text, lines);
                    return out == null || out.isBlank() ? "(no line" + (text == null ? ")" : " holds \"" + text + "\")")
                            : out.strip();
                });
            }
            case Device.Processes processes -> withDevice(processes.instance(), device -> {
                String filter = processes.name() == null ? "" : processes.name().strip();
                List<String> rows = processRows(device.shell(PS), filter);
                return rows.isEmpty() ? "No process" + (filter.isEmpty() ? "" : " holds \"" + filter + "\"") + "."
                        : String.join("\n", rows);
            });
            case Device.Crash crash -> {
                String pkg = pkg(crash.pkg());
                yield withDevice(crash.instance(), device -> {
                    // By pid: `am crash <package>` hit LDPlayer's WebView renderer, a process of the same package.
                    String pid = mainPid(device, pkg);
                    String said = device.shell("am crash " + pid);
                    return "Sent a crash to " + pkg + " (pid " + pid + ")" + (said == null || said.isBlank() ? "."
                            : ": " + said.strip());
                });
            }
            case Device.Push push -> {
                String to = devicePath(push.to());
                if (PUSH_ROOTS.stream().noneMatch(to::startsWith)) {
                    throw new IllegalArgumentException("push writes only under " + WRITABLE + ".");
                }
                Path file = pushable(push.file(), UserDirs.config());
                String size = size(file.toFile().length());
                yield withDevice(push.instance(), device -> {
                    try {
                        device.pushData(file.toFile(), to);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return "Copied " + file.getFileName() + " (" + size + ") to " + to + ".";
                });
            }
            case Device.Pull pull -> {
                String path = devicePath(pull.path());
                Path pulls = UserDirs.cache().resolve("pulls");
                yield withDevice(pull.instance(), device -> {
                    try {
                        Files.createDirectories(pulls);
                        Path to = pulledTo(pulls, path);
                        device.pull(path, to.toFile());
                        return "Copied " + path + " to " + to + " (" + size(Files.size(to)) + ").";
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        };
    }

    /** Android's {@code ps}, a line per process: pid, resident memory in kB, name. */
    static final String PS = "ps -A -o PID,RSS,NAME";

    /** {@code ps}'s rows whose name, the last column, holds {@code filter} (all of them for a blank one), no header. */
    static List<String> processRows(String ps, String filter) {
        if (ps == null) return List.of();
        return ps.lines().skip(1).map(String::strip).filter(row -> !row.isEmpty())
                .filter(row -> filter.isEmpty() || row.substring(row.lastIndexOf(' ') + 1).contains(filter)).toList();
    }

    /**
     * {@code logcat}'s command: dumped, of one process when {@code pid} is set, its last {@code lines} cut after the
     * level's filter, since {@code -t} cuts before it (MuMu, 2026-11-11: {@code -t 5 '*:W'} printed nothing).
     */
    static String logcatCommand(int lines, String pid, String level) {
        return "logcat -d" + (pid == null ? "" : " --pid=" + pid) + (level == null ? "" : " '*:" + level + "'")
                + (lines > 0 ? " | tail -n " + lines : "");
    }

    /**
     * The last {@code lines} of {@code log} that hold {@code text}, in any case: matched here, as plain text, so no
     * pattern reaches the device's shell.
     */
    static String lastHolding(String log, String text, int lines) {
        if (log == null) return "";
        String wanted = text.toLowerCase(Locale.ROOT);
        List<String> held = log.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains(wanted)).toList();
        return String.join("\n", held.subList(Math.max(0, held.size() - lines), held.size()));
    }

    /** {@code bytes} as people read a file's size. */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return bytes / 1024 + " kB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /** {@code level} as logcat's letter, or null when not given. */
    static String logLevel(String level) {
        if (level == null || level.isBlank()) return null;
        String said = level.strip().substring(0, 1).toUpperCase(Locale.ROOT);
        if (!LOG_LEVEL.matcher(said).matches()) {
            throw new IllegalArgumentException("\"" + level + "\" is no log level: V, D, I, W, E or F.");
        }
        return said;
    }

    /**
     * The pid of the process named {@code pkg}, its main one; the app's others are named {@code pkg:…}. Several of that
     * very name are refused rather than guessed between.
     */
    private static String mainPid(AdbDevice device, String pkg) {
        String said = device.shell("pidof " + pkg);
        List<String> pids = said == null || said.isBlank() ? List.of() : List.of(said.strip().split("\\s+"));
        if (pids.isEmpty() || !pids.getFirst().matches("\\d+")) {
            throw new IllegalArgumentException(pkg + " is not running.");
        }
        if (pids.size() > 1) {
            throw new IllegalArgumentException("Several processes are called " + pkg + " (" + String.join(", ", pids)
                    + "); processes tells them apart.");
        }
        return pids.getFirst();
    }

    /** {@code path} when it is an absolute device path of plain characters with no {@code ..}. */
    static String devicePath(String path) {
        String said = path == null ? "" : path.strip();
        if (!DEVICE_PATH.matcher(said).matches() || said.contains("..")) {
            throw new IllegalArgumentException("\"" + path + "\" is no absolute path on the instance (letters, "
                    + "digits and . / _ - + @ only).");
        }
        return said;
    }

    /**
     * {@code file} when it is an existing file on this computer, given absolute, that holds nothing private: not under
     * {@code settings} (BotMaker's, its credentials among them) and nothing hidden on its way ({@code .ssh}, {@code .git}).
     */
    static Path pushable(String file, Path settings) {
        Path path = file == null || file.isBlank() ? null : Path.of(file.strip());
        if (path == null || !path.isAbsolute() || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(file + " is no file on this computer; give its absolute path.");
        }
        Path real;
        Path kept;
        try {
            real = path.toRealPath();
            kept = Files.exists(settings) ? settings.toRealPath() : settings.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException(file + " cannot be read: " + e.getMessage());
        }
        boolean hidden = false;
        for (Path part : real) hidden |= part.toString().startsWith(".");
        if (hidden || real.startsWith(kept)) {
            throw new IllegalArgumentException("push copies no hidden file and nothing of BotMaker's settings.");
        }
        return real;
    }

    /** Where a pull of {@code devicePath} lands in {@code pulls}: its name, numbered past any file already there. */
    static Path pulledTo(Path pulls, String devicePath) {
        String name = devicePath.substring(devicePath.lastIndexOf('/') + 1);
        if (name.isEmpty() || name.equals(".")) {
            throw new IllegalArgumentException("pull copies a file, not " + devicePath + ".");
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Path landing = pulls.resolve(name);
        for (int n = 2; Files.exists(landing) || Files.exists(landing.resolveSibling(landing.getFileName() + ".part"));
             n++) {
            landing = pulls.resolve(stem + "-" + n + extension);
        }
        return landing;
    }

    /** The products' status lines, then one line per instance: its reference, product, state and adb address. */
    static String listed(Platforms.DiscoveryReport report) {
        List<String> lines = new ArrayList<>();
        report.statuses().forEach(status -> lines.add(status.statusLine()));
        if (report.instances().isEmpty()) {
            lines.add("No instance found.");
        } else {
            lines.add("");
            for (EmulatorInstance instance : report.instances()) {
                lines.add(instance.reference() + "  (" + instance.brand() + ", " + instance.state().displayName()
                        + ", adb " + instance.endpoint() + (instance.canStop() ? "" : ", no stop command") + ")");
            }
        }
        return String.join("\n", lines);
    }

    private static String searched(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("search_store needs a query.");
        try {
            List<PlayStoreSearch.StoreApp> found = PlayStoreSearch.search(query.strip());
            if (found.isEmpty()) return "Google Play found nothing for \"" + query.strip() + "\".";
            return String.join("\n", found.stream().map(app -> app.name() + "  " + app.packageName()).toList());
        } catch (IOException e) {
            return "Google Play did not answer: " + e.getMessage();
        }
    }

    private static String installed(Device.Install install) {
        EmulatorInstance instance = instance(install.instance());
        if (install.file() != null && !install.file().isBlank()) {
            Path file = Path.of(install.file().strip());
            if (!file.isAbsolute() || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException(install.file() + " is no file on this computer; give its absolute "
                        + "path.");
            }
            return result(EmulatorInstall.fromFile(running(instance), file, null));
        }
        if (install.pkg() == null || install.pkg().isBlank()) {
            throw new IllegalArgumentException("install needs a file, or a package for its Play page.");
        }
        return result(EmulatorInstall.openStorePage(running(instance), pkg(install.pkg()), null));
    }

    private static String result(EmulatorInstall.Result result) {
        if (!result.ok()) throw new IllegalArgumentException(result.message());
        return result.message();
    }

    private static String outcome(EmulatorAppLauncher.Outcome outcome) {
        if (!outcome.ok()) throw new IllegalArgumentException(outcome.message());
        return outcome.message();
    }

    /**
     * {@code command} when it only reads: it starts with one of {@link #SHELL_READS}, a {@link #BARE_READS} alone and
     * a dumpsys with no word that acts, and it carries nothing a shell would read as a second command or a redirect.
     */
    static String readOnly(String command) {
        String said = command == null ? "" : command.strip();
        boolean reads = SHELL_READS.stream().anyMatch(read -> said.equals(read) || said.startsWith(read + " "))
                && BARE_READS.stream().noneMatch(read -> said.startsWith(read + " "))
                && !(said.startsWith("dumpsys ") && DUMPSYS_ACTS.matcher(said).find());
        if (!reads || SHELL_SPECIAL.matcher(said).find()) {
            throw new IllegalArgumentException("shell runs only a read: " + String.join(", ", SHELL_READS)
                    + " (wm size and wm density alone; no dumpsys set, reset, unplug and the like), with no "
                    + "; & | < > ` $ \\ or line break.");
        }
        return said;
    }

    /** {@code pkg} when it reads as an Android package. */
    private static String pkg(String pkg) {
        String said = pkg == null ? "" : pkg.strip();
        if (!PlayStoreSearch.PACKAGE.matcher(said).matches()) {
            throw new IllegalArgumentException("\"" + pkg + "\" is not an app's package, like com.example.game.");
        }
        return said;
    }

    /** The discovered instance {@code reference} names. */
    private static EmulatorInstance instance(String reference) {
        return EmulatorInstances.find(reference).orElseThrow(() -> new IllegalArgumentException("No emulator "
                + "instance is called \"" + reference + "\"; device's list names them."));
    }

    /** {@code instance} when it runs, so adb reaches it. */
    private static EmulatorInstance running(EmulatorInstance instance) {
        if (!EmulatorLiveness.running(instance)) {
            throw new IllegalArgumentException(instance.name() + " is not running; device's start starts it.");
        }
        return instance;
    }

    /** {@code use}'s answer over a connection to the running instance {@code reference} names. */
    private static <T> T withDevice(String reference, Function<AdbDevice, T> use) {
        EmulatorInstance instance = running(instance(reference));
        try (AdbDevice device = AdbDevice.connect(instance.adb())) {
            return use.apply(device);
        }
    }

    /** What a fix needs here: no bot, so no size to hold the screen to, and a start that brings the instance up. */
    private static Fixes.Context fixes(EmulatorInstance instance) {
        return new Fixes.Context(instance, null, words -> EmulatorReadiness.bringUp(instance, words).isPresent()
                ? null : EmulatorReadiness.notReady(instance));
    }
}
