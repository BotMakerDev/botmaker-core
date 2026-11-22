// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The local branches, and how they stand against a remote's by the last fetch (39 §9): no network here. Each public
 * method is documented on {@link ProjectVcs}.
 */
final class GitBranches {

    private final GitFolder folder;

    GitBranches(GitFolder folder) {
        this.folder = folder;
    }

    List<String> branches() throws IOException {
        if (!folder.isRepo()) return List.of();
        try (Git git = folder.open()) {
            String current = git.getRepository().getBranch();
            List<String> out = new ArrayList<>();
            for (Ref ref : git.branchList().call()) out.add(Repository.shortenRefName(ref.getName()));
            return out.stream().filter(b -> b.equals(current) || !RepairPoints.isSavedBranch(b)).sorted().toList();
        } catch (Exception e) {
            throw new IOException("Could not list the branches: " + e.getMessage(), e);
        }
    }

    void createBranch(String name) throws IOException {
        folder.ensureInitialized();
        try (Git git = folder.open()) {
            if (!Repository.isValidRefName("refs/heads/" + name)) {
                throw new IOException("“" + name + "” cannot be a branch name.");
            }
            git.branchCreate().setName(name).call();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not create " + name + ": " + e.getMessage(), e);
        }
    }

    int switchTo(String name) throws IOException {
        if (folder.merging()) throw new IOException("A merge is in progress — finish or cancel it first.");
        try (Git git = folder.open()) {
            if (!git.status().call().isClean()) throw new IOException("Save your changes before switching.");
            Repository repo = git.getRepository();
            ObjectId before = repo.resolve("HEAD^{tree}");
            git.checkout().setName(name).call();
            ObjectId after = repo.resolve("HEAD^{tree}");
            if (Objects.equals(before, after)) return 0;
            try (TreeWalk walk = new TreeWalk(repo)) {
                walk.setRecursive(true);
                walk.addTree(before);
                walk.addTree(after);
                walk.setFilter(TreeFilter.ANY_DIFF);
                int changed = 0;
                while (walk.next()) changed++;
                return changed;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not switch to " + name + ": " + e.getMessage(), e);
        }
    }

    void deleteBranch(String name) throws IOException {
        try (Git git = folder.open()) {
            if (name.equals(git.getRepository().getBranch())) {
                throw new IOException("Switch to another branch before deleting " + name + ".");
            }
            git.branchDelete().setBranchNames(name).setForce(false).call();
        } catch (NotMergedException e) {
            throw new IOException(name + " has versions no other branch has; merge it first.", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not delete " + name + ": " + e.getMessage(), e);
        }
    }

    boolean mergedInto(Remote remote, String branch, String into) {
        if (!folder.isRepo()) return false;
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            Ref line = repo.exactRef(Constants.R_REMOTES + remote.id() + "/" + branch);
            Ref target = repo.exactRef(Constants.R_REMOTES + remote.id() + "/" + into);
            if (line == null || target == null) return false;
            return walk.isMergedInto(walk.parseCommit(line.getObjectId()), walk.parseCommit(target.getObjectId()));
        } catch (IOException e) {
            return false;
        }
    }

    boolean inLocal(Remote remote, String branch, String local) {
        if (!folder.isRepo()) return false;
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            Ref line = repo.exactRef(Constants.R_REMOTES + remote.id() + "/" + branch);
            Ref here = repo.exactRef(Constants.R_HEADS + local);
            if (line == null || here == null) return false;
            return walk.isMergedInto(walk.parseCommit(line.getObjectId()), walk.parseCommit(here.getObjectId()));
        } catch (IOException e) {
            return false;
        }
    }

    boolean hasRemoteBranch(Remote remote, String branch) {
        if (!folder.isRepo()) return false;
        try (Git git = folder.open()) {
            return git.getRepository().exactRef(Constants.R_REMOTES + remote.id() + "/" + branch) != null;
        } catch (IOException e) {
            return false;
        }
    }

    String branch() throws IOException {
        try (Git git = folder.open()) {
            return git.getRepository().getBranch();
        }
    }

    int notIn(Remote remote, String remoteBranch) throws IOException {
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            Ref there = repo.exactRef("refs/remotes/" + remote.id() + "/" + remoteBranch);
            if (head == null) return 0;
            if (there == null) return -1;
            return count(walk, head, there.getObjectId());
        }
    }

    /** How many commits reachable from {@code from} are not reachable from {@code not}. */
    static int count(RevWalk walk, ObjectId from, ObjectId not) throws IOException {
        walk.reset();
        walk.markStart(walk.parseCommit(from));
        walk.markUninteresting(walk.parseCommit(not));
        int n = 0;
        for (RevCommit ignored : walk) n++;
        return n;
    }
}
