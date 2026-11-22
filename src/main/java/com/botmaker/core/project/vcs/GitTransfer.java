// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.vcs;

import com.botmaker.shared.github.GitHubConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything that goes over the network: fetches, pushes, the clone, and joining another history to this one. The
 * token is passed per call and never written, and goes only to GitHub ({@link #tokenFor}). Each public method is
 * documented on {@link ProjectVcs}.
 */
final class GitTransfer {

    private final GitFolder folder;
    private final GitRemotes remotes;

    GitTransfer(GitFolder folder, GitRemotes remotes) {
        this.folder = folder;
        this.remotes = remotes;
    }

    void fetchTag(Remote remote, String tag, String token) throws IOException {
        try (Git git = folder.open()) {
            Repository repo = git.getRepository();
            ObjectId had = repo.resolve("refs/tags/" + tag + "^{commit}");
            if (had != null && repo.exactRef(GitTags.OWN_RELEASES + tag) != null) {
                // One tag name, two releases: the fork's, made here, and the author's, asked for now.
                throw new IOException("Your own copy was published as " + tag + ", the number the author has "
                        + "since used for their release, so it cannot be taken here.");
            }
            var fetch = git.fetch().setRemote(remote.id())
                    .setRefSpecs(new RefSpec("refs/tags/" + tag + ":refs/tags/" + tag))
                    .setTagOpt(TagOpt.NO_TAGS);
            String sent = tokenFor(remotes.remoteUrl(remote), token);
            if (sent != null) fetch.setCredentialsProvider(credentials(sent));
            var result = fetch.call();
            var update = result.getTrackingRefUpdate("refs/tags/" + tag);
            if (update != null && update.getResult() == RefUpdate.Result.REJECTED) {
                throw new IOException("The release " + tag + " was changed by its author after you took it, "
                        + "so nothing was updated.");
            }
            ObjectId now = repo.resolve("refs/tags/" + tag + "^{commit}");
            if (now == null) throw new IOException("The release " + tag + " is gone from the original.");
            if (had != null && !had.equals(now)) {
                throw new IOException("The release " + tag + " was changed by its author after you took it, "
                        + "so nothing was updated.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not fetch " + tag + ": " + e.getMessage(), e);
        }
    }

    void deleteRemoteBranch(Remote remote, String branch, String token) throws IOException {
        String url = remotes.remoteUrl(remote);
        if (url == null) throw new IOException("This project has no '" + remote.id() + "' remote.");
        String sent = tokenFor(url, token);
        String tracking = Constants.R_REMOTES + remote.id() + "/" + branch;
        try (Git git = folder.open()) {
            Ref seen = git.getRepository().exactRef(tracking);
            if (seen == null) throw new IOException(branch + " was never read from " + remote.id() + "; fetch first.");
            var push = git.push().setRemote(remote.id()).setRefSpecs(new RefSpec(":refs/heads/" + branch))
                    .setRefLeaseSpecs(new RefLeaseSpec("refs/heads/" + branch, seen.getObjectId().name()));
            if (sent != null) push.setCredentialsProvider(credentials(sent));
            for (PushResult result : push.call()) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    if (update.getStatus() == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                        throw new IOException(branch + " on " + remote.id() + " has versions this computer has "
                                + "not read, so it was not deleted.");
                    }
                    if (update.getStatus() != RemoteRefUpdate.Status.NON_EXISTING) checkUpdate(update);
                }
            }
            RefUpdate gone = git.getRepository().updateRef(tracking);
            gone.setForceUpdate(true);
            gone.delete();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw pushFailure(url, e);
        }
    }

    String push(Remote remote, String remoteBranch, String token, boolean force) throws IOException {
        if (token == null || token.isBlank()) throw new IOException("Not signed in to GitHub.");
        return push(remote.id(), remoteBranch, token, force);
    }

    String push(String remoteName, String remoteBranch, String token, boolean force) throws IOException {
        if (!folder.isRepo()) throw new IOException("Nothing to push — this project has no history yet.");
        String url = remotes.remoteUrl(remoteName);
        if (url == null) throw new IOException("This project has no '" + remoteName + "' remote yet.");
        String sent = tokenFor(url, token);
        try (Git git = folder.open()) {
            String branch = git.getRepository().getBranch();
            List<RefSpec> specs = new ArrayList<>();
            specs.add(new RefSpec((force ? "+" : "") + "refs/heads/" + branch + ":refs/heads/" + remoteBranch));
            boolean notes = git.getRepository().exactRef(ProjectVcs.NAMES) != null;
            if (notes) specs.add(new RefSpec(ProjectVcs.NAMES + ":" + ProjectVcs.NAMES));
            var push = git.push()
                    .setRemote(remoteName)
                    .setRefSpecs(specs)
                    .setPushTags()
                    .setForce(false);
            if (sent != null) push.setCredentialsProvider(credentials(sent));
            Iterable<PushResult> results = push.call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    // Names are a courtesy: a notes ref that moved on the other side must not fail the push.
                    if (!update.getRemoteName().equals(ProjectVcs.NAMES)) checkUpdate(update);
                }
            }
            return branch;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw pushFailure(url, e);
        }
    }

    /**
     * What a failed push throws. An explained refusal carries <b>no cause</b>: the Versions tab reports a
     * failure's deepest cause ({@code ShareActions.rootMessage}), so with JGit's exception under it the
     * explanation never reached the screen (2026-10-01). JGit's words go to the log instead.
     */
    static IOException pushFailure(String url, Exception e) {
        String raw = "Push failed: " + e.getMessage();
        String explained = explainRefusal(url, raw);
        if (explained.equals(raw)) return new IOException(raw, e);
        System.err.println(raw);
        return new IOException(explained);
    }

    /**
     * {@code message}, or what a refused push means when GitHub refused it. JGit's words for GitHub's 403 are
     * "git-receive-pack not permitted", and on an organization's repository the usual cause is not the account
     * but the app: an organization with third-party access restrictions (GitHub's default) lets Studio's token
     * read its public repositories and refuses every push until an owner grants Studio access (2026-10-01,
     * BotMakerDev's gamebot). A terminal push over SSH still works, which is what made it look like a Studio bug.
     */
    static String explainRefusal(String url, String message) {
        if (message == null || !message.contains("not permitted")) return message;
        String where = SyncModel.slug(url).map(s -> s.owner() + "/" + s.repo()).orElse(url);
        String owner = SyncModel.slug(url).map(SyncModel.Slug::owner).orElse("the owner");
        return "GitHub refused the push to " + where + ". If " + owner + " is an organization, it has not "
                + "granted BotMaker Studio access: an owner of " + owner + " approves it at https://github.com/"
                + "organizations/" + owner + "/settings/oauth_application_policy (or you request it at "
                + "https://github.com/settings/connections/applications/"
                + GitHubConfig.OAUTH_CLIENT_ID + "). Otherwise your account cannot "
                + "write to it.";
    }

    void fetch(String remoteName, String token) throws IOException {
        String sent = tokenFor(remotes.remoteUrl(remoteName), token);
        try (Git git = folder.open()) {
            var fetch = git.fetch().setRemote(remoteName).setTagOpt(TagOpt.FETCH_TAGS);
            if (sent != null) fetch.setCredentialsProvider(credentials(sent));
            fetch.call();
        } catch (Exception e) {
            throw new IOException("Could not read " + remoteName + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@code token} when {@code url} is a GitHub HTTPS address, else null: the user's GitHub token is theirs to
     * give GitHub, and a remote added by hand may be any host ({@code 39} §10 — a token goes nowhere else).
     */
    static String tokenFor(String url, String token) {
        if (url == null || token == null || token.isBlank()) return null;
        return url.trim().toLowerCase(Locale.ROOT).startsWith("https://github.com/") ? token : null;
    }

    List<String> remoteTags(Remote remote, String token) throws IOException {
        String url = remotes.remoteUrl(remote);
        if (url == null) return List.of();
        try {
            var ls = Git.lsRemoteRepository().setRemote(url).setTags(true).setHeads(false).setTimeout(15);
            String sent = tokenFor(url, token);
            if (sent != null) ls.setCredentialsProvider(credentials(sent));
            List<String> out = new ArrayList<>();
            for (Ref ref : ls.call()) {
                String name = Repository.shortenRefName(ref.getName());
                if (!name.endsWith("^{}")) out.add(name);
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not list the original's releases: " + e.getMessage(), e);
        }
    }

    static IOException changedSinceApproved(String tag) {
        return new IOException("The release " + tag + " changed after the catalog approved it: its tag points at "
                + "another commit now, so nothing was taken.");
    }

    static void cloneAt(String url, String tag, String commit, Path dest, String token) throws IOException {
        var clone = Git.cloneRepository().setURI(url).setDirectory(dest.toFile())
                .setRemote(Remote.ORIGINAL.id()).setNoCheckout(true);
        String sent = tokenFor(url, token);
        if (sent != null) clone.setCredentialsProvider(credentials(sent));
        try (Git git = clone.call()) {
            ObjectId at = git.getRepository().resolve("refs/tags/" + tag + "^{commit}");
            if (at == null) throw new IOException("The release " + tag + " is not in " + url + ".");
            if (commit != null && !commit.isBlank() && !at.name().equals(commit)) throw changedSinceApproved(tag);
            // The clone made a local branch at the author's default tip (named whatever theirs is): the user's
            // line is main, at the tag, whatever the author did after it.
            Repository repo = git.getRepository();
            RefUpdate main = repo.updateRef("refs/heads/main");
            main.setNewObjectId(at);
            main.setForceUpdate(true);
            main.update();
            repo.updateRef("HEAD").link("refs/heads/main");
            git.reset().setMode(ResetType.HARD).setRef(at.name()).call();
            if (!"main".equals(repo.getBranch())) {
                throw new IOException("Could not start a branch at " + tag + ".");
            }
            // Build output must never become a version, whatever the author's repository ignores.
            GitFolder.writeGitignore(dest);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not install from " + url + ": " + e.getMessage(), e);
        }
    }

    boolean attach(String url, String tag, String label, String token) throws IOException {
        folder.ensureInitialized();
        if (remotes.remoteUrl(Remote.ORIGINAL) == null) remotes.setRemote(Remote.ORIGINAL, url);
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            var fetch = git.fetch().setRemote(Remote.ORIGINAL.id())
                    .setRefSpecs(new RefSpec("+refs/tags/" + tag + ":refs/tags/" + tag))
                    .setTagOpt(TagOpt.NO_TAGS);
            String sent = tokenFor(remotes.remoteUrl(Remote.ORIGINAL), token);
            if (sent != null) fetch.setCredentialsProvider(credentials(sent));
            fetch.call();
            ObjectId tagged = repo.resolve("refs/tags/" + tag + "^{commit}");
            if (tagged == null) throw new IOException("The release " + tag + " is not in " + url + ".");
            RevCommit head = walk.parseCommit(repo.resolve("HEAD"));
            RevCommit base = walk.parseCommit(tagged);
            if (walk.isMergedInto(base, head)) return false;
            keepMine(repo, head, base, VersionOrigin.INSTALL, label);
            return true;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not link to the original: " + e.getMessage(), e);
        }
    }

    boolean joinUnrelated(Remote remote, String remoteBranch, String label, String token) throws IOException {
        if (!folder.isRepo() || remotes.remoteUrl(remote) == null) return false;
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            String tracking = "refs/remotes/" + remote.id() + "/" + remoteBranch;
            var fetch = git.fetch().setRemote(remote.id())
                    .setRefSpecs(new RefSpec("+refs/heads/" + remoteBranch + ":" + tracking))
                    .setTagOpt(TagOpt.NO_TAGS);
            String sent = tokenFor(remotes.remoteUrl(remote), token);
            if (sent != null) fetch.setCredentialsProvider(credentials(sent));
            try {
                fetch.call();
            } catch (TransportException missing) {
                // An empty repository has no such branch; there is nothing to join.
                return false;
            }
            Ref there = repo.exactRef(tracking);
            ObjectId headId = repo.resolve("HEAD");
            if (there == null || headId == null) return false;
            RevCommit head = walk.parseCommit(headId);
            RevCommit theirs = walk.parseCommit(there.getObjectId());
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(head);
            walk.markStart(theirs);
            if (walk.next() != null) return false;
            keepMine(repo, head, theirs, VersionOrigin.PUBLISH, label);
            return true;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read your copy on GitHub: " + e.getMessage(), e);
        }
    }

    /**
     * Moves {@code HEAD} to a new merge commit of {@code head} and {@code other} whose tree is {@code head}'s:
     * the histories are joined, and no file moves.
     */
    private void keepMine(Repository repo, RevCommit head, RevCommit other, VersionOrigin origin, String label)
            throws IOException {
        CommitBuilder merge = new CommitBuilder();
        merge.setTreeId(head.getTree());
        merge.setParentIds(head, other);
        merge.setAuthor(folder.now());
        merge.setCommitter(folder.now());
        merge.setMessage(origin.stamp(label));
        ObjectId id;
        try (ObjectInserter inserter = repo.newObjectInserter()) {
            id = inserter.insert(merge);
            inserter.flush();
        }
        RefUpdate update = repo.updateRef("HEAD");
        update.setNewObjectId(id);
        update.setExpectedOldObjectId(head);
        update.setRefLogMessage(label, false);
        switch (update.update()) {
            case FAST_FORWARD, NEW, FORCED -> { }
            default -> throw new IOException("Could not record " + label + ".");
        }
    }

    /** How GitHub takes an OAuth token over HTTPS — per call, never stored. */
    private static UsernamePasswordCredentialsProvider credentials(String token) {
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    /** Turns one ref's push outcome into a readable failure; a successful/no-op update passes silently. */
    private static void checkUpdate(RemoteRefUpdate update) throws IOException {
        switch (update.getStatus()) {
            case OK, UP_TO_DATE -> { /* pushed, or already there */ }
            case REJECTED_NONFASTFORWARD -> throw new IOException(
                    "Your copy on GitHub has versions this computer doesn't, so nothing was overwritten.");
            case REJECTED_OTHER_REASON -> throw new IOException("Push rejected: "
                    + (update.getMessage() == null ? "the remote refused the update." : update.getMessage()));
            default -> throw new IOException("Push failed (" + update.getStatus() + ")"
                    + (update.getMessage() == null ? "." : ": " + update.getMessage()));
        }
    }
}
