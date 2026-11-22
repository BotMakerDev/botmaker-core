// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.vcs;

import com.botmaker.core.project.vcs.ProjectVcs.RemoteInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The remotes as {@code .git/config} holds them (39 §2, §9): their names and addresses, no network here. Each public
 * method is documented on {@link ProjectVcs}.
 */
final class GitRemotes {

    private final GitFolder folder;

    GitRemotes(GitFolder folder) {
        this.folder = folder;
    }

    String remoteUrl(Remote remote) {
        return remoteUrl(remote.id());
    }

    String remoteUrl(String name) {
        if (!folder.isRepo() || name == null) return null;
        try (Git git = folder.open()) {
            String url = git.getRepository().getConfig().getString("remote", name, "url");
            return url == null || url.isBlank() ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    List<RemoteInfo> remotes() throws IOException {
        if (!folder.isRepo()) return List.of();
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            String branch = repo.getBranch();
            ObjectId head = repo.resolve("HEAD");
            List<RemoteInfo> out = new ArrayList<>();
            for (String name : new TreeSet<>(repo.getRemoteNames())) {
                Ref there = repo.exactRef("refs/remotes/" + name + "/" + branch);
                int ahead = -1;
                int behind = -1;
                if (there != null && head != null) {
                    ahead = GitBranches.count(walk, head, there.getObjectId());
                    behind = GitBranches.count(walk, there.getObjectId(), head);
                }
                out.add(new RemoteInfo(name, remoteUrl(name), ahead, behind));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not list the remotes: " + e.getMessage(), e);
        }
    }

    void addRemote(String name, String url) throws IOException {
        String problem = remoteProblem(name, url);
        if (problem != null) throw new IOException(problem);
        if (remoteUrl(name) != null) throw new IOException("A remote named " + name + " exists already.");
        folder.ensureInitialized();
        try (Git git = folder.open()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", name, "url", url.trim());
            config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/" + name + "/*");
            config.save();
        } catch (Exception e) {
            throw new IOException("Could not add the remote: " + e.getMessage(), e);
        }
    }

    /** Why {@code name}/{@code url} cannot be added as a remote, or null when they can. */
    static String remoteProblem(String name, String url) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) return "A remote name is letters, digits, . _ or -.";
        if (url == null) return "The address is empty.";
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "That is not an address.";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return "Only https:// addresses can be added.";
        }
        if (uri.getRawUserInfo() != null) return "An address must not carry a user name or a token.";
        return null;
    }

    void setRemote(Remote remote, String url) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("Remote URL must not be empty.");
        folder.ensureInitialized();
        try (Git git = folder.open()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", remote.id(), "url", url.trim());
            config.setString("remote", remote.id(), "fetch", "+refs/heads/*:refs/remotes/" + remote.id() + "/*");
            config.save();
        } catch (Exception e) {
            throw new IOException("Could not set the remote: " + e.getMessage(), e);
        }
    }

    void removeRemote(Remote remote) throws IOException {
        if (!folder.isRepo() || remoteUrl(remote) == null) return;
        try (Git git = folder.open()) {
            git.remoteRemove().setRemoteName(remote.id()).call();
            // JGit unsets the config section only.
            Repository repo = git.getRepository();
            for (Ref tracking : repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + remote.id() + "/")) {
                RefUpdate drop = repo.updateRef(tracking.getName());
                drop.setForceUpdate(true);
                drop.delete();
            }
        } catch (Exception e) {
            throw new IOException("Could not remove the remote " + remote.id() + ": " + e.getMessage(), e);
        }
    }

    boolean adoptRemoteNames() throws IOException {
        if (!folder.isRepo()) return false;
        boolean shared = !Files.isDirectory(folder.projectDir().resolve(".git"));
        try (Git git = folder.open()) {
            Repository repo = git.getRepository();
            boolean changed = false;
            for (Remote remote : Remote.values()) changed |= rename(repo, remote.oldId(), remote.id(), shared);
            StoredConfig config = repo.getConfig();
            String url = config.getString("remote", Remote.MINE.id(), "url");
            if (!shared && url != null && !url.startsWith("https://")) {
                Optional<String> https = SyncModel.slug(url).map(s -> "https://github.com/" + s + ".git");
                if (https.isPresent()) {
                    config.setString("remote", Remote.MINE.id(), "url", https.get());
                    config.save();
                    changed = true;
                }
            }
            return changed;
        } catch (Exception e) {
            throw new IOException("Could not rename the remotes: " + e.getMessage(), e);
        }
    }

    /** Renames remote {@code from} to {@code to} as {@link ProjectVcs#adoptRemoteNames} says; whether it did. */
    private static boolean rename(Repository repo, String from, String to, boolean shared) throws IOException {
        StoredConfig config = repo.getConfig();
        String url = config.getString("remote", from, "url");
        if (url == null) return false;
        String taken = config.getString("remote", to, "url");
        if (taken != null && !sameRepository(taken, url)) {
            String aside = "old-" + to;
            if (shared || config.getString("remote", aside, "url") != null) return false;
            move(repo, to, aside);
        }
        move(repo, from, to);
        return true;
    }

    private static boolean sameRepository(String a, String b) {
        Optional<SyncModel.Slug> slug = SyncModel.slug(a);
        return a.equals(b) || slug.isPresent() && slug.equals(SyncModel.slug(b));
    }

    /**
     * Moves remote {@code from} to {@code to}: its config when {@code to} has none, its remote-tracking refs
     * that {@code to} lacks, and every branch tracking it. Nothing of {@code from} is left.
     */
    private static void move(Repository repo, String from, String to) throws IOException {
        StoredConfig config = repo.getConfig();
        if (config.getString("remote", to, "url") == null) {
            for (String key : config.getNames("remote", from)) {
                List<String> values = List.of(config.getStringList("remote", from, key)).stream()
                        .map(v -> key.equals("fetch") ? v.replace(Constants.R_REMOTES + from + "/",
                                Constants.R_REMOTES + to + "/") : v)
                        .toList();
                config.setStringList("remote", to, key, values);
            }
        }
        config.unsetSection("remote", from);
        for (String branch : config.getSubsections("branch")) {
            if (from.equals(config.getString("branch", branch, "remote"))) {
                config.setString("branch", branch, "remote", to);
            }
        }
        config.save();
        String prefix = Constants.R_REMOTES + from + "/";
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix(prefix)) {
            String name = Constants.R_REMOTES + to + "/" + ref.getName().substring(prefix.length());
            // A symbolic HEAD a command-line clone leaves is not copied: the next fetch writes what it needs.
            if (!ref.isSymbolic() && repo.exactRef(name) == null && ref.getObjectId() != null) {
                RefUpdate moved = repo.updateRef(name);
                moved.setNewObjectId(ref.getObjectId());
                moved.forceUpdate();
            }
            RefUpdate gone = repo.getRefDatabase().newUpdate(ref.getName(), true);
            gone.setForceUpdate(true);
            gone.delete();
        }
    }
}
