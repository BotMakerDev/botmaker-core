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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code device}: the Android emulators and phones on this computer, with no project open (2026-11-10). It is the
 * core's own, built on shared's emulator layer alone, so it serves with no Studio and no bot: list the instances,
 * start and stop one, read its checks and run their fixes, find and install a game, start and stop it, look at the
 * screen and touch it. A bot's {@code setup} and {@code launch_game} act on the bot's own target; this one names
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
    }

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
                + "stop_app, restart_app, focus, screenshot, tap, swipe, key, shell. Name an instance as list does.")
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
                return "Tapped " + tap.x() + "," + tap.y() + ".";
            });
            case Device.Swipe swipe -> withDevice(swipe.instance(), device -> {
                long ms = swipe.ms() == null ? 300 : Math.clamp(swipe.ms(), 1, 10_000);
                device.swipe(swipe.x1(), swipe.y1(), swipe.x2(), swipe.y2(), ms);
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
        };
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
