// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.run;

import com.botmaker.plugin.api.assist.ToolSchema;
import com.botmaker.plugin.api.run.RunEntry;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.core.plugin.PluginHost;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The runs the project's plugins declare beside the bot's {@code main} ({@code runs(MyRuns.class)}, 2026-11-03):
 * each a title and a class Studio launches as it launches the bot, the bot's value holders as its arguments. Studio
 * knows nothing else of one — what it walks or checks is the plugin's.
 */
public final class RunEntries {

    private RunEntries() {}

    /** One declared run, with the plugin that declared it. */
    public record Declared(PluginCoordinate plugin, String pluginName, Path jar, RunEntry entry) {

        public String title() {
            return entry.title();
        }

        public String mainClass() {
            return entry.mainClass();
        }

        /**
         * Whether it gets a toolbar button: not when its plugin's window starts it, nor while it needs arguments, which
         * a button cannot give until Studio has a form for them (web discovery plan, phase 4).
         */
        public boolean onBar() {
            return entry.onBar() && !entry.needsArguments();
        }

        /**
         * Its title and what it takes, one line: {@code Keep exploring (minutes: integer, optional — minutes at most;
         * …)}; the title alone for one that takes nothing.
         */
        public String described() {
            List<ToolSchema.Field> fields = entry.schema().fields();
            if (fields.isEmpty()) return title();
            return title() + " (" + fields.stream().map(f -> f.name() + ": " + f.kind().jsonType()
                    + (f.choices().isEmpty() ? "" : " " + String.join("|", f.choices()))
                    + (f.optional() ? ", optional" : "")
                    + (f.description().isEmpty() ? "" : " — " + f.description())).collect(Collectors.joining("; "))
                    + ")";
        }
    }

    /** Every loaded plugin's runs, in plugin order; a plugin that cannot list its own costs only those. */
    public static List<Declared> all() {
        List<Declared> out = new ArrayList<>();
        for (LoadedPlugin plugin : PluginHost.plugins()) {
            try {
                List<RunEntry> entries = plugin.plugin().runEntries();
                if (entries == null) continue;
                for (RunEntry entry : entries) {
                    out.add(new Declared(plugin.coordinate(), plugin.name(), plugin.jar(), entry));
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without runs: it declares none.
                System.err.println("Warning: " + plugin.coordinate() + " could not offer its runs: " + e);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The run of {@code declared} titled {@code title}, case and spacing aside; empty when none is.
     *
     * @throws IllegalArgumentException when two plugins declare that title, naming both
     */
    public static Optional<Declared> named(List<Declared> declared, String title) {
        String wanted = key(title);
        List<Declared> found = declared.stream().filter(d -> key(d.title()).equals(wanted)).toList();
        if (found.size() > 1) {
            throw new IllegalArgumentException("Several plugins declare a run titled '" + title.strip() + "': "
                    + found.stream().map(d -> d.plugin().toString()).collect(Collectors.joining(", ")) + ".");
        }
        return found.stream().findFirst();
    }

    /**
     * {@link #named} over {@link #all()}.
     *
     * @throws IllegalArgumentException when no plugin declares {@code title}, listing the titles that are
     */
    public static Declared require(String title) {
        List<Declared> declared = all();
        return named(declared, title).orElseThrow(() -> new IllegalArgumentException(
                "No plugin declares a run titled '" + title.strip() + "'. Declared: " + (declared.isEmpty() ? "none"
                        : declared.stream().map(Declared::described).collect(Collectors.joining(", "))) + "."));
    }

    private static String key(String title) {
        return title == null ? "" : title.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
