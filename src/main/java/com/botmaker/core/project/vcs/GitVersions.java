// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.vcs;

import com.botmaker.core.project.vcs.ProjectVcs.CommitInfo;
import com.botmaker.core.project.vcs.ProjectVcs.FileStatus;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * A project's versions: taking one, listing and naming them, what each changed, the working tree against the last,
 * and going back to one. Each public method is documented on {@link ProjectVcs}.
 */
final class GitVersions {

    private final GitFolder folder;

    GitVersions(GitFolder folder) {
        this.folder = folder;
    }

    String checkpoint(VersionOrigin origin, String label) throws IOException {
        folder.ensureInitialized();
        // Mid-update, "add everything" would record conflict markers as the user's decision.
        if (folder.merging()) throw new IOException("An update is in progress — finish or cancel it in the Versions window.");
        try (Git git = folder.open()) {
            // Status first, then only what it names: JGit's add of "." hashes every tracked file whatever its stat
            // says, 2.3 s a call on a bot with 70 MB of screens, and it ran twice before each plugin write (2026-10-19).
            Status status = git.status().call();
            if (status.isClean()) return null;
            Set<String> changed = new TreeSet<>(status.getModified());
            changed.addAll(status.getUntracked());
            if (!changed.isEmpty()) {
                AddCommand add = git.add();
                changed.forEach(add::addFilepattern);
                add.call();
            }
            if (!status.getMissing().isEmpty()) {
                RmCommand rm = git.rm().setCached(true);
                status.getMissing().forEach(rm::addFilepattern);
                rm.call();
            }
            String first = label == null || label.isBlank() ? origin.displayName() : label;
            RevCommit c = git.commit().setAuthor(folder.now()).setCommitter(folder.now())
                    .setMessage(origin.stamp(first)).call();
            return c.abbreviate(7).name();
        } catch (Exception e) {
            throw new IOException("Commit failed: " + e.getMessage(), e);
        }
    }

    String checkpointOnly(VersionOrigin origin, String label, Collection<String> paths) throws IOException {
        folder.ensureInitialized();
        if (folder.merging()) throw new IOException("An update is in progress — finish or cancel it in the Versions window.");
        try (Git git = folder.open()) {
            StatusCommand asked = git.status();
            paths.forEach(asked::addPath);
            if (asked.call().isClean()) return null;
            AddCommand add = git.add();
            paths.forEach(add::addFilepattern);
            add.call();
            String first = label == null || label.isBlank() ? origin.displayName() : label;
            CommitCommand commit = git.commit().setAuthor(folder.now()).setCommitter(folder.now())
                    .setMessage(origin.stamp(first));
            paths.forEach(commit::setOnly);
            return commit.call().abbreviate(7).name();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Commit failed: " + e.getMessage(), e);
        }
    }

    List<CommitInfo> history() throws IOException {
        if (!folder.isRepo()) return List.of();
        try (Git git = folder.open()) {
            Map<String, List<String>> tagsByCommit = GitTags.tagsByCommit(git);
            Map<String, String> names = names(git);
            List<CommitInfo> out = new ArrayList<>();
            for (RevCommit c : git.log().call()) {
                String sha = c.name();
                out.add(new CommitInfo(sha, c.abbreviate(7).name(), c.getShortMessage(),
                        VersionOrigin.of(c.getFullMessage()), names.get(sha), c.getAuthorIdent().getName(),
                        Instant.ofEpochSecond(c.getCommitTime()), tagsByCommit.getOrDefault(sha, List.of())));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not read project history: " + e.getMessage(), e);
        }
    }

    void name(String sha, String name) throws IOException {
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit c = walk.parseCommit(git.getRepository().resolve(sha));
            if (name == null || name.isBlank()) {
                git.notesRemove().setNotesRef(ProjectVcs.NAMES).setObjectId(c).call();
            } else {
                git.notesAdd().setNotesRef(ProjectVcs.NAMES).setObjectId(c).setMessage(name.strip()).call();
            }
        } catch (Exception e) {
            throw new IOException("Could not name that version: " + e.getMessage(), e);
        }
    }

    SortedMap<String, VcsFileStatus> changes(String sha) throws IOException {
        SortedMap<String, VcsFileStatus> out = new TreeMap<>();
        try (Git git = folder.open(); DiffFormatter fmt = new DiffFormatter(OutputStream.nullOutputStream())) {
            for (DiffEntry e : entries(git.getRepository(), fmt, sha, null)) {
                switch (e.getChangeType()) {
                    case ADD, COPY -> out.put(e.getNewPath(), VcsFileStatus.ADDED);
                    case DELETE -> out.put(e.getOldPath(), VcsFileStatus.DELETED);
                    default -> out.put(e.getNewPath(), VcsFileStatus.MODIFIED);
                }
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not read what " + sha + " changed: " + e.getMessage(), e);
        }
    }

    String diff(String sha, String relativePath) throws IOException {
        try (Git git = folder.open(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter fmt = new DiffFormatter(out)) {
            for (DiffEntry e : entries(git.getRepository(), fmt, sha, relativePath)) fmt.format(e);
            fmt.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Could not diff " + relativePath + " in " + sha + ": " + e.getMessage(), e);
        }
    }

    /** What version {@code sha} changed against its first parent, of {@code path} alone unless it is null. */
    static List<DiffEntry> entries(Repository repo, DiffFormatter fmt, String sha, String path)
            throws IOException {
        fmt.setRepository(repo);
        fmt.setDetectRenames(true);
        if (path != null) fmt.setPathFilter(PathFilter.create(path));
        try (RevWalk walk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            ObjectId id = repo.resolve(sha);
            if (id == null) throw new IOException("Unknown version: " + sha);
            RevCommit c = walk.parseCommit(id);
            CanonicalTreeParser after = new CanonicalTreeParser();
            after.reset(reader, c.getTree());
            if (c.getParentCount() == 0) {
                return fmt.scan(new EmptyTreeIterator(), after);
            }
            CanonicalTreeParser before = new CanonicalTreeParser();
            before.reset(reader, walk.parseCommit(c.getParent(0)).getTree());
            return fmt.scan(before, after);
        }
    }

    /** Version names by commit id, read off {@link ProjectVcs#NAMES}. */
    private static Map<String, String> names(Git git) throws Exception {
        Map<String, String> out = new HashMap<>();
        if (git.getRepository().exactRef(ProjectVcs.NAMES) == null) return out;
        for (Note note : git.notesList().setNotesRef(ProjectVcs.NAMES).call()) {
            String text = new String(git.getRepository().open(note.getData()).getBytes(), StandardCharsets.UTF_8);
            if (!text.isBlank()) out.put(note.name(), text.strip());
        }
        return out;
    }

    FileStatus status() throws IOException {
        if (!folder.isRepo()) return FileStatus.empty();
        try (Git git = folder.open()) {
            Status s = git.status().call();
            SortedSet<String> added = new TreeSet<>(s.getAdded());
            added.addAll(s.getChanged());                 // staged modifications count as added-to-index
            SortedSet<String> modified = new TreeSet<>(s.getModified());
            SortedSet<String> removed = new TreeSet<>(s.getRemoved());
            removed.addAll(s.getMissing());               // tracked-but-deleted-on-disk
            SortedSet<String> untracked = new TreeSet<>(s.getUntracked());
            return new FileStatus(added, modified, removed, untracked);
        } catch (Exception e) {
            throw new IOException("Could not read project status: " + e.getMessage(), e);
        }
    }

    String diff(String relativePath) throws IOException {
        if (!folder.isRepo()) return "";
        try (Git git = folder.open(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter fmt = new DiffFormatter(out)) {
            Repository repo = git.getRepository();
            fmt.setRepository(repo);
            fmt.setPathFilter(PathFilter.create(relativePath));

            ObjectId head = repo.resolve("HEAD^{tree}");
            List<DiffEntry> entries;
            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                if (head != null) oldTree.reset(reader, head);
                // HEAD tree (or an empty one for a repo with no commit) vs the working directory.
                entries = fmt.scan(head != null ? oldTree : new EmptyTreeIterator(),
                        new FileTreeIterator(repo));
            }
            for (DiffEntry e : entries) {
                fmt.format(e);
            }
            fmt.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Could not diff " + relativePath + ": " + e.getMessage(), e);
        }
    }

    void discard(String relativePath) throws IOException {
        folder.ensureInitialized();
        try (Git git = folder.open()) {
            git.checkout().addPath(relativePath).call();
        } catch (Exception e) {
            throw new IOException("Could not discard " + relativePath + ": " + e.getMessage(), e);
        }
    }

    String restoreTo(String sha, VersionOrigin origin, String label) throws IOException {
        folder.ensureInitialized();
        try (Git git = folder.open()) {
            Repository repo = git.getRepository();
            ObjectId target = repo.resolve(sha);
            if (target == null) throw new IOException("Unknown commit: " + sha);

            if (!git.status().call().isClean()) {
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setAuthor(folder.now()).setCommitter(folder.now())
                        .setMessage(VersionOrigin.SAFETY.stamp("Snapshot before rollback")).call();
            }
            ObjectId tip = repo.resolve("HEAD");
            String shortTarget = target.abbreviate(7).name();

            // Hard-reset the working tree/index to the target, then soft-reset HEAD back to the tip so the
            // recovered content lands as one new commit — the intervening commits stay reachable in reflog.
            git.reset().setMode(ResetType.HARD).setRef(target.name()).call();
            git.reset().setMode(ResetType.SOFT).setRef(tip.name()).call();
            if (!git.status().call().isClean()) {
                git.commit().setAuthor(folder.now()).setCommitter(folder.now())
                        .setMessage(origin.stamp(label == null ? "Roll back to " + shortTarget : label)).call();
            }
            return repo.resolve("HEAD").name();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Rollback failed: " + e.getMessage(), e);
        }
    }

    Map<String, byte[]> filesAtHead(Predicate<String> wanted) throws IOException {
        if (folder.headCommit().isEmpty()) throw new IOException("This project has no version yet.");
        Map<String, byte[]> out = new TreeMap<>();
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository());
             TreeWalk tree = new TreeWalk(git.getRepository())) {
            tree.addTree(walk.parseCommit(git.getRepository().resolve(Constants.HEAD)).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (!wanted.test(path)) continue;
                FileMode mode = tree.getFileMode(0);
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                    throw new IOException(path + " is a link or a submodule, not a file.");
                }
                out.put(path, git.getRepository().open(tree.getObjectId(0)).getBytes());
            }
        }
        return out;
    }
}
