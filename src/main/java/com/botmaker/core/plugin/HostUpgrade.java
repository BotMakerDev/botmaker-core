package com.botmaker.core.plugin;

import com.botmaker.core.migrate.Migration;
import com.botmaker.core.migrate.Rewrite;
import com.botmaker.core.project.managed.ManagedRepairs;
import com.botmaker.core.services.ContractDependency;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.index.BotIndex;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.PluginCoordinate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A host folder repaired for a newer version of its plugin, the way a bot's values are on that plugin's upgrade
 * (umbrella CLAUDE.md § Plugin platform: nothing converts a managed value). The plugin's {@code @Migrate} rules rewrite
 * what they name first, read from the older version's sources jar when its jar is on this PC; then
 * {@link ManagedRepairs} removes a value whose id is gone and resets one that no longer compiles to its first value,
 * marked {@code @Refactor}.
 *
 * <p>Pure: the folder's sources in, a plan out, nothing written. {@link HostValues} writes it under its lock.
 */
final class HostUpgrade {

    private HostUpgrade() {}

    /**
     * The repair of {@code sources}, the folder's classes, for {@code plugin}'s move from {@code from} to {@code to}.
     *
     * @param declared  the values of this PC {@code to} declares
     * @param classpath {@code to}'s jar and what it needs
     */
    static ManagedRepairs.Planned plan(Path folder, Map<Path, String> sources, List<ManagedValue<?>> declared, ValueGrammar grammar,
                        List<Path> classpath, PluginCoordinate plugin, String from, String to) {
        List<String> path = classpath.stream().map(Path::toString).toList();
        boolean markable = ContractDependency.onClasspath(path, com.botmaker.plugin.api.meta.Refactor.class);
        Map<Path, String> now = new LinkedHashMap<>(sources);
        List<String> repaired = new ArrayList<>();
        List<String> left = new ArrayList<>();
        BotIndex index = BotIndex.uncached(now, path, folder);

        Optional<List<String>> older = olderClasspath(path, plugin.artifactId(), from, to);
        if (older.isPresent()) {
            Rewrite.Result result = Migration.of(index, older.get()).plan(markable);
            if (result.outcome() instanceof Refactor.Planned planned) {
                // Only the folder's own classes: a rule's probe is not one of them.
                planned.rewrites().forEach((file, text) -> {
                    if (now.containsKey(file)) now.put(file, text);
                });
                if (!result.applied().isEmpty()) {
                    index = index.replacing(now);
                    repaired.add(result.applied().size() + " use(s) rewritten by " + plugin.artifactId()
                            + "'s @Migrate rules.");
                }
            }
            result.left().forEach(each -> left.add(each.why()));
        } else {
            left.add(plugin.artifactId() + " " + from + "'s jar is not on this PC, so its @Migrate rules were not"
                    + " applied.");
        }

        String note = markable ? "Reset by the " + plugin.artifactId() + " " + to + " upgrade: it no longer compiled."
                : null;
        ManagedRepairs.Planned values = ManagedRepairs.plan(index, declared, grammar, note);
        now.putAll(values.rewrites());
        values.deleted().forEach(now::remove);
        repaired.addAll(values.repaired());
        left.addAll(values.left());

        Map<Path, String> rewrites = new LinkedHashMap<>();
        now.forEach((file, text) -> {
            if (!text.equals(sources.get(file))) rewrites.put(file, text);
        });
        Set<Path> deleted = new LinkedHashSet<>(sources.keySet());
        deleted.removeAll(now.keySet());
        return new ManagedRepairs.Planned(rewrites, deleted, repaired, left);
    }

    /**
     * {@code classpath} with {@code to}'s jar swapped for {@code from}'s, found beside it in the repository layout
     * ({@code <artifact>/<version>/<artifact>-<version>.jar}); empty when either is not there.
     */
    static Optional<List<String>> olderClasspath(List<String> classpath, String artifact, String from, String to) {
        if (from.isEmpty() || to.isEmpty()) return Optional.empty();
        String name = artifact + "-" + to + ".jar";
        List<String> out = new ArrayList<>(classpath);
        for (int i = 0; i < out.size(); i++) {
            Path jar = Path.of(out.get(i));
            Path versionDir = jar.getParent();
            if (!jar.getFileName().toString().equals(name) || versionDir == null
                    || versionDir.getParent() == null || !versionDir.getFileName().toString().equals(to)) continue;
            Path old = versionDir.getParent().resolve(from).resolve(artifact + "-" + from + ".jar");
            if (!Files.isRegularFile(old)) return Optional.empty();
            out.set(i, old.toString());
            return Optional.of(List.copyOf(out));
        }
        return Optional.empty();
    }
}
