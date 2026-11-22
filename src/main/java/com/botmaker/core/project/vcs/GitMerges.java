// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.vcs;

import com.botmaker.core.project.vcs.ProjectVcs.Merge;
import com.botmaker.core.project.vcs.ProjectVcs.Side;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/**
 * A merge of the author's tag or of a branch, held half done while its conflicts are decided (39 §7). Each public
 * method is documented on {@link ProjectVcs}.
 */
final class GitMerges {

    private final GitFolder folder;

    GitMerges(GitFolder folder) {
        this.folder = folder;
    }

    Merge mergeTag(String tag) throws IOException {
        try (Git git = folder.open()) {
            if (!git.status().call().isClean()) {
                throw new IOException("Save your changes as a version before updating.");
            }
            ObjectId at = git.getRepository().resolve("refs/tags/" + tag + "^{commit}");
            if (at == null) throw new IOException("The release " + tag + " is not on this computer.");
            return merge(git, at);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not update: " + e.getMessage(), e);
        }
    }

    Merge mergeRef(String ref) throws IOException {
        try (Git git = folder.open()) {
            if (!git.status().call().isClean()) {
                throw new IOException("Save your changes as a version before merging.");
            }
            ObjectId at = git.getRepository().resolve(ref + "^{commit}");
            if (at == null) throw new IOException(Repository.shortenRefName(ref) + " is not on this computer.");
            return merge(git, at);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not merge: " + e.getMessage(), e);
        }
    }

    /** No fast-forward, never committed: the caller finishes it ({@link #finishMerge}) or cancels it. */
    private static Merge merge(Git git, ObjectId at) throws Exception {
        MergeResult result = git.merge().include(at)
                .setCommit(false)
                .setFastForward(FastForwardMode.NO_FF)
                .setStrategy(MergeStrategy.RECURSIVE)
                .call();
        return switch (result.getMergeStatus()) {
            case ALREADY_UP_TO_DATE -> new Merge(true, List.of());
            case CONFLICTING -> new Merge(false, List.copyOf(new TreeSet<>(result.getConflicts().keySet())));
            case MERGED_NOT_COMMITTED, MERGED, FAST_FORWARD, MERGED_SQUASHED, MERGED_SQUASHED_NOT_COMMITTED,
                 FAST_FORWARD_SQUASHED -> new Merge(false, List.of());
            default -> throw new IOException("The merge could not start (" + result.getMergeStatus() + ").");
        };
    }

    String mergeRelease() throws IOException {
        try (Git git = folder.open()) {
            List<ObjectId> heads = git.getRepository().readMergeHeads();
            if (heads == null || heads.isEmpty()) return null;
            ObjectId head = heads.getFirst();
            return GitTags.tagsByCommit(git).getOrDefault(head.name(), List.of(head.abbreviate(7).name())).getFirst();
        } catch (Exception e) {
            throw new IOException("Could not read the update: " + e.getMessage(), e);
        }
    }

    List<String> unresolved() throws IOException {
        try (Git git = folder.open()) {
            return List.copyOf(new TreeSet<>(git.status().call().getConflicting()));
        } catch (Exception e) {
            throw new IOException("Could not read the update: " + e.getMessage(), e);
        }
    }

    void resolve(String path, Side side) throws IOException {
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            ObjectId from = side == Side.MINE ? repo.resolve("HEAD") : repo.readMergeHeads().getFirst();
            byte[] content = null;
            try (TreeWalk tw = TreeWalk.forPath(
                    repo, path, walk.parseCommit(from).getTree())) {
                if (tw != null) content = repo.open(tw.getObjectId(0)).getBytes();
            }
            Path file = folder.projectDir().resolve(path);
            if (content == null) {
                Files.deleteIfExists(file);
                git.rm().addFilepattern(path).call();
            } else {
                Files.createDirectories(file.getParent());
                Files.write(file, content);
                git.add().addFilepattern(path).call();
            }
        } catch (Exception e) {
            throw new IOException("Could not decide " + path + ": " + e.getMessage(), e);
        }
    }

    String finishMerge(VersionOrigin origin, String label) throws IOException {
        if (!unresolved().isEmpty()) throw new IOException("Some files are still to decide.");
        try (Git git = folder.open()) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            RevCommit c = git.commit().setAuthor(folder.now()).setCommitter(folder.now())
                    .setMessage(origin.stamp(label)).call();
            return c.abbreviate(7).name();
        } catch (Exception e) {
            throw new IOException("Could not finish the update: " + e.getMessage(), e);
        }
    }

    void abortMerge() throws IOException {
        try (Git git = folder.open()) {
            git.reset().setMode(ResetType.HARD).setRef("HEAD").call();
            Repository repo = git.getRepository();
            repo.writeMergeHeads(null);
            repo.writeMergeCommitMsg(null);
        } catch (Exception e) {
            throw new IOException("Could not cancel the update: " + e.getMessage(), e);
        }
    }
}
