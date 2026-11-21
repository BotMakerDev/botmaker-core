// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.plugin;

import com.botmaker.core.events.FileEdit;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.index.nav.Refactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A together in the core (2026-11-21, {@code History.together}): its writes queued as they are asked, then made as one
 * step, or none of them — every file put back as it was and a class it made deleted — at the first refused.
 */
class TogetherTest {

    private static final PluginWrite GONE = new PluginWrite.SetValue("com.example:none:GONE", null);

    @TempDir
    Path projects;

    private ProjectConfig config;
    private Path first;
    private Path second;
    private String firstWas;
    private String secondWas;
    private final List<String> steps = new ArrayList<>();
    private HostPluginValues values;

    @BeforeEach
    void project() throws IOException {
        config = ProjectConfig.forProject("Mybot", projects);
        Path dir = config.mainPackageDir();
        Files.createDirectories(dir);
        first = dir.resolve("First.java").toAbsolutePath().normalize();
        second = dir.resolve("Second.java").toAbsolutePath().normalize();
        firstWas = "package " + config.mainPackage() + ";\n\nclass First {}\n";
        secondWas = "package " + config.mainPackage() + ";\n\nclass Second {}\n";
        Files.writeString(first, firstWas);
        Files.writeString(second, secondWas);
        values = HostPluginValues.writing(config, new ProjectState(), null, new HostPluginValues.Edits() {
            @Override
            public void step(String label, List<FileEdit> edits) {
                steps.add(label + ": " + edits.size());
            }

            @Override
            public void redraw(String content) {
            }
        });
    }

    @AfterEach
    void noValues() {
        HostPluginValues.clear();
    }

    @Test
    void everyWriteLandsAsOneStep() throws IOException {
        Optional<String> answer = values.performAll("Tidy the map", List.of(rewrite(first, "class First { int a; }"),
                rewrite(second, "class Second { int b; }"), rewrite(first, "class First { int c; }")));

        assertEquals(Optional.empty(), answer);
        assertTrue(Files.readString(first).contains("int c;"));
        assertTrue(Files.readString(second).contains("int b;"));
        assertEquals(List.of("Tidy the map: 2"), steps);
    }

    @Test
    void aRefusalLeavesTheBotAsItWas() throws IOException {
        Optional<String> refusal = values.perform(GONE);

        Optional<String> answer = values.performAll("Tidy the map", List.of(rewrite(first, "class First { int a; }"),
                rewrite(second, "class Second { int b; }"), GONE, rewrite(first, "class First { int c; }")));

        assertTrue(refusal.isPresent());
        assertEquals(refusal, answer);
        assertEquals(firstWas, Files.readString(first));
        assertEquals(secondWas, Files.readString(second));
        assertEquals(List.of(), steps);
    }

    @Test
    void aClassMadeThenRefusedIsDeleted() {
        Path made = config.mainPackageDir().resolve("Made.java");
        Path checks = config.mainPackageDir().resolve("Checks.java");

        assertEquals(Optional.empty(), values.performAll("Make", List.of(
                new PluginWrite.CreateBooleanMethod("Made", "ready"))));
        Optional<String> answer = values.performAll("Make and fail", List.of(
                new PluginWrite.CreateBooleanMethod("Checks", "ready"), GONE));

        assertTrue(Files.isRegularFile(made), "a together that lands makes its class");
        assertTrue(answer.isPresent());
        assertFalse(Files.exists(checks), "the class of a together taken back is deleted, not left empty");
    }

    @Test
    void insideAHeldBatchOnlyTheTogetherIsUndone() throws IOException {
        WriteSteps.Held batch = WriteSteps.hold("Record the walk");
        List<String> recorded = new ArrayList<>();

        batch.during(() -> values.perform(rewrite(first, "class First { int a; }")));
        Optional<String> answer = batch.during(() -> values.performAll("Tidy the map", List.of(
                rewrite(first, "class First { int z; }"), rewrite(second, "class Second { int b; }"), GONE)));
        batch.finish((label, edits) -> recorded.add(label + ": " + edits.stream()
                .map(edit -> edit.path().getFileName().toString()).toList()));

        assertTrue(answer.isPresent());
        assertTrue(Files.readString(first).contains("int a;"), "the batch's own write stays");
        assertEquals(secondWas, Files.readString(second));
        assertEquals(List.of("Record the walk: [First.java]"), recorded);
    }

    @Test
    void queuedWritesAnswerAtOnceAndNestedOnesJoin() throws IOException {
        HostPluginValues.install(config, new ProjectState(), null, null);
        List<Optional<String>> answers = new ArrayList<>();
        List<PluginWrite> inner = new ArrayList<>();

        List<PluginWrite> queued = HostPluginValues.queued(() -> {
            answers.add(HostPluginValues.applyRename(plan(first, "class First { int a; }")));
            inner.addAll(HostPluginValues.queued(() ->
                    answers.add(HostPluginValues.applyRename(plan(second, "class Second { int b; }")))));
        });

        assertEquals(2, queued.size());
        assertEquals(List.of(), inner, "an inner together joins the outer one");
        assertEquals(List.of(Optional.empty(), Optional.empty()), answers);
        assertEquals(firstWas, Files.readString(first), "nothing is made while it is queued");
    }

    private PluginWrite rewrite(Path file, String body) {
        return PluginWrite.ApplyPlan.of(plan(file, body));
    }

    private Refactor.Planned plan(Path file, String body) {
        return new Refactor.Planned("Write " + file.getFileName(),
                Map.of(file, "package " + config.mainPackage() + ";\n\n" + body + "\n"));
    }
}
