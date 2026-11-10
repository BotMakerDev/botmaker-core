package com.botmaker.core.plugin;

import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.History;
import com.botmaker.plugin.api.PluginLog;
import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.Theme;
import com.botmaker.plugin.api.source.PluginValues;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The services a home plugin's tool is given, with no bot open (2026-11-10): no screen, and every service bound to a
 * bot (its folders, runs, values) refuses with {@link NeedsBot} rather than answering for a bot that is not there, so a
 * tool that needs one says so instead of writing into the core's working directory. The history is
 * {@link History#NONE}, total by the contract.
 */
public final class HomeServices implements StudioServices {

    /** What a home plugin's tool met when it reached for a bot: its message is the answer the assistant gets. */
    public static final class NeedsBot extends IllegalStateException {
        public NeedsBot() {
            super("That needs a bot open: open_project opens one, then call it again.");
        }
    }

    private static final StudioServices BARE = HostServices.forProject(null, UiPort.NONE);

    @Override
    public Path projectDir() {
        throw new NeedsBot();
    }

    @Override
    public Path resourcesDir() {
        throw new NeedsBot();
    }

    @Override
    public Optional<Path> stateDir() {
        return Optional.empty();
    }

    @Override
    public boolean hasUi() {
        return false;
    }

    @Override
    public Theme theme() {
        return BARE.theme();
    }

    @Override
    public Dialogs dialogs() {
        return BARE.dialogs();
    }

    @Override
    public Runs runs() {
        return refusing(Runs.class);
    }

    @Override
    public PluginValues pluginValues() {
        return refusing(PluginValues.class);
    }

    /** {@link History#NONE}, which the contract makes total: a batch of writes that needs no bot still runs. */
    @Override
    public History history() {
        return History.NONE;
    }

    @Override
    public void status(String message) {
        BARE.status(message);
    }

    @Override
    public PluginLog log() {
        return BARE.log();
    }

    /** {@code type} whose every call refuses with {@link NeedsBot}; its Object methods answer as an object's. */
    static <T> T refusing(Class<T> type) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) ->
                switch (method.getName()) {
                    case "toString" -> "no bot's " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> throw new NeedsBot();
                });
        return type.cast(proxy);
    }
}
