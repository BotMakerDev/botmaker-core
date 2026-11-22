// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.vcs;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One project's repository for the classes {@link ProjectVcs} delegates to: where it is, who writes in it, opening
 * it, the state it is in, and what it keeps out of every version. Each public method is documented on
 * {@link ProjectVcs}.
 */
final class GitFolder {

    /** An exported bot file: tens of MB a version or a push would carry (2026-10-25, finding N3). */
    private static final String BMK = "*.bmk";

    /**
     * Names git should ignore. Mirrors {@code ProjectArchive}'s publish exclusions for build output, and adds
     * the local-only editor state {@code ProjectArchive} also excludes but that additionally shouldn't clutter
     * local history: the Reader/Editor opt-in marker, and Studio's editor state under {@code .botmaker/}.
     * (Provenance, {@code botmaker-source.json}, is deliberately left tracked — it is worth versioning so a
     * rollback keeps a bot's origin.)
     */
    private static final List<String> GITIGNORE_LINES = List.of(
            "target/", ".idea/", ".gradle/", "build/", "out/", "*.class",
            ProjectMode.MARKER, "/" + ProjectConfig.STUDIO_DIR + "/", BMK);

    /**
     * What {@link #excludeStudioState} keeps out of every version: Studio's state, an old Studio's leftover, and
     * this computer's values of the bot's schemas ({@link ProjectVcs#VALUES}).
     */
    private static final List<String> EXCLUDED = List.of("/" + ProjectConfig.STUDIO_DIR + "/",
            "/src/main/resources/botmaker-project.properties", BMK, ProjectVcs.VALUES);

    private final Path projectDir;
    private final PersonIdent author;

    GitFolder(Path projectDir, PersonIdent author) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.author = author;
    }

    Path projectDir() {
        return projectDir;
    }

    /** Who writes, as of now: a {@code PersonIdent} keeps the time it was made, and {@link #author} is long-lived. */
    PersonIdent now() {
        return new PersonIdent(author, Instant.now());
    }

    boolean isRepo() {
        return gitDir() != null;
    }

    boolean sharedRepository() {
        return Files.isRegularFile(projectDir.resolve(".git"));
    }

    /**
     * The repository's own directory: {@code .git} itself, or the directory a {@code .git} file's
     * {@code gitdir:} line names (relative to the project). Null when there is neither.
     */
    Path gitDir() {
        Path dotGit = projectDir.resolve(".git");
        if (Files.isDirectory(dotGit)) return dotGit;
        if (!Files.isRegularFile(dotGit)) return null;
        try {
            for (String line : Files.readAllLines(dotGit)) {
                if (!line.startsWith("gitdir:")) continue;
                Path dir = projectDir.resolve(line.substring("gitdir:".length()).trim()).normalize();
                return Files.isDirectory(dir) ? dir : null;
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    void init(VersionOrigin origin, String label) throws IOException {
        if (isRepo()) return;
        try (Git git = Git.init().setDirectory(projectDir.toFile()).call()) {
            writeGitignore(projectDir);
            excludeStudioState();
            git.add().addFilepattern(".").call();
            git.commit().setAuthor(now()).setCommitter(now()).setMessage(origin.stamp(label)).call();
        } catch (Exception e) {
            throw new IOException("Failed to initialize project history: " + e.getMessage(), e);
        }
    }

    void ensureInitialized() throws IOException {
        if (!isRepo()) init(VersionOrigin.CREATE, "Initial commit");
    }

    Git open() throws IOException {
        excludeStudioState();
        Path gitDir = gitDir();
        if (gitDir == null) throw new IOException("This project has no history yet.");
        Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setWorkTree(projectDir.toFile())
                .readEnvironment()
                .build();
        return new Git(repo);
    }

    String head() throws IOException {
        if (!isRepo()) return "";
        try (Git git = open()) {
            ObjectId id = git.getRepository().resolve("HEAD");
            return id == null ? "" : id.name();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read the project's head: " + e.getMessage(), e);
        }
    }

    Optional<String> headCommit() {
        if (!isRepo()) return Optional.empty();
        try (Git git = open()) {
            return Optional.ofNullable(git.getRepository().resolve(Constants.HEAD)).map(ObjectId::name);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    boolean merging() {
        if (!isRepo()) return false;
        try (Git git = open()) {
            return git.getRepository().getRepositoryState() != RepositoryState.SAFE;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Keeps Studio's {@code .botmaker/} out of every version, whatever the project's {@code .gitignore} says:
     * a line in {@code .git/info/exclude}, which git reads exactly like a {@code .gitignore} and which never
     * leaves this machine. The {@code .gitignore} is the user's (and, for a clone, the author's), and one
     * written before 2026-09-26 does not name the directory — so the rule goes where only this checkout sees
     * it rather than into a file that would then show up as a change of theirs.
     *
     * <p>{@code botmaker-project.properties} beside it since 2026-10-01: an older Studio wrote it and nothing
     * reads it since 2026-09-27, so a copy left on disk was swept into the next automatic version (the gamebot
     * template's, one push from its {@code main}). Excluding it deletes nothing and leaves a project that
     * already tracks it as it is — git never un-tracks a file for an exclude line.
     *
     * <p>{@code *.bmk} since 2026-10-25: an export written into the project folder (finding N3). Here rather than
     * in the {@code .gitignore} of a project made before, for the same reason as {@code .botmaker/}.
     */
    private void excludeStudioState() throws IOException {
        Path gitDir = gitDir();
        if (gitDir == null) return;
        Path info = gitDir.resolve("info");
        Path exclude = info.resolve("exclude");
        String existing = Files.exists(exclude) ? Files.readString(exclude) : "";
        StringBuilder added = new StringBuilder();
        for (String line : EXCLUDED) {
            if (existing.lines().noneMatch(l -> l.trim().equals(line))) added.append(line).append(System.lineSeparator());
        }
        if (added.isEmpty()) return;
        Files.createDirectories(info);
        String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : System.lineSeparator();
        Files.writeString(exclude, existing + separator + added);
    }

    /** {@code projectDir}'s {@code .gitignore}, unless it has one. */
    static void writeGitignore(Path projectDir) throws IOException {
        Path gitignore = projectDir.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        Files.writeString(gitignore, String.join(System.lineSeparator(), GITIGNORE_LINES)
                + System.lineSeparator());
    }
}
