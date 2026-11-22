// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Git history for one user project, backed by JGit (no system git binary): versions ({@link #checkpoint}),
 * names, restores, and the two remotes of {@code docs/refactor/39-versions.md} §2 — {@link Remote#MINE} and
 * {@link Remote#ORIGINAL} — with a clone ({@link #cloneAt}), a fetch, a push and the attach of a zip install
 * ({@link #attach}). Restore rewinds the working tree to an earlier commit's content as a new commit on top of
 * the current tip, so nothing is ever lost. <b>A token is passed per call and never written</b> — not to
 * {@code .git/config}, not into a remote URL.
 *
 * <p>Blocking / does file + git I/O — intended to run off the FX thread (the VCS panel wraps calls in a
 * background task).
 *
 * <p>The work is in one class per kind, over one {@link GitFolder}: {@link GitVersions}, {@link GitMerges},
 * {@link GitBranches}, {@link GitRemotes}, {@link GitTransfer} (the network) and {@link GitTags}.
 */
public final class ProjectVcs {

    /**
     * One entry in the project history: {@code message} is the first line, {@code origin} who wrote it (from
     * the trailer), {@code name} what the user called it afterwards (null if nothing), {@code tags} the
     * (possibly empty) list of tags pointing here.
     */
    public record CommitInfo(String sha, String shortSha, String message, VersionOrigin origin, String name,
                             String author, Instant when, List<String> tags) {

        /** What the timeline calls it: the user's name for it, else its first line. */
        public String title() {
            return name != null ? name : message;
        }

        /** A milestone somebody chose: a saved version, or any version given a name since. */
        public boolean milestone() {
            return name != null || origin == VersionOrigin.SAVE;
        }
    }

    /** Where version names live: notes, so naming a version never rewrites it (39-versions.md §4). */
    public static final String NAMES = "refs/notes/botmaker-names";

    /**
     * Every {@code params} package of the bot's sources (2026-11-04): this computer's parameters, whole, which never
     * reach a version, a push or a publish; their starting copy, {@code starting-params/}, does. A restore keeps
     * them: a hard reset leaves an excluded file alone. A file already tracked there stays tracked.
     */
    public static final String VALUES = "/src/main/java/**/params/";

    /**
     * The working tree's changes relative to {@code HEAD}, bucketed by kind, as POSIX-style relative paths.
     * {@code added}/{@code modified}/{@code removed} are tracked changes staged or unstaged; {@code untracked}
     * is new files git isn't yet following. Empty sets when the tree is clean (or the project isn't a repo).
     */
    public record FileStatus(SortedSet<String> added, SortedSet<String> modified,
                             SortedSet<String> removed, SortedSet<String> untracked) {

        public boolean isClean() {
            return added.isEmpty() && modified.isEmpty() && removed.isEmpty() && untracked.isEmpty();
        }

        /** All changed paths, sorted, each mapped to how it differs from the last commit. */
        public SortedMap<String, VcsFileStatus> labelled() {
            SortedMap<String, VcsFileStatus> out = new TreeMap<>();
            untracked.forEach(p -> out.put(p, VcsFileStatus.NEW));
            added.forEach(p -> out.put(p, VcsFileStatus.ADDED));
            modified.forEach(p -> out.put(p, VcsFileStatus.MODIFIED));
            removed.forEach(p -> out.put(p, VcsFileStatus.DELETED));
            return out;
        }

        public static FileStatus empty() {
            return new FileStatus(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        }
    }

    private static final PersonIdent DEFAULT_AUTHOR =
            new PersonIdent("BotMaker Studio", "studio@botmaker.local");

    /** The system property JGit reads, on each hash, for which SHA-1 to use. */
    private static final String SHA1_IMPLEMENTATION = "org.eclipse.jgit.util.sha1.implementation";

    static {
        // The JDK's SHA-1, not JGit's own: JGit's checks every block for a collision attack in plain Java, and a
        // checkpoint hashing a screen's 1 MB shot spent most of an assistant's picture cut there (2026-10-16).
        // A project's history is local, written by Studio alone, so there is no forged object to catch.
        if (System.getProperty(SHA1_IMPLEMENTATION) == null) System.setProperty(SHA1_IMPLEMENTATION, "jdkNative");
    }

    private final GitFolder folder;
    private final GitVersions versions;
    private final GitMerges merges;
    private final GitBranches branches;
    private final GitRemotes remotes;
    private final GitTransfer transfer;
    private final GitTags tags;

    public ProjectVcs(Path projectDir) {
        this(projectDir, DEFAULT_AUTHOR);
    }

    /** Uses {@code name}/{@code email} (e.g. the signed-in GitHub identity) as the commit author. */
    public ProjectVcs(Path projectDir, String name, String email) {
        this(projectDir, new PersonIdent(
                name == null || name.isBlank() ? DEFAULT_AUTHOR.getName() : name,
                email == null || email.isBlank() ? DEFAULT_AUTHOR.getEmailAddress() : email));
    }

    private ProjectVcs(Path projectDir, PersonIdent author) {
        this.folder = new GitFolder(projectDir, author);
        this.versions = new GitVersions(folder);
        this.merges = new GitMerges(folder);
        this.branches = new GitBranches(folder);
        this.remotes = new GitRemotes(folder);
        this.transfer = new GitTransfer(folder, remotes);
        this.tags = new GitTags(folder);
    }

    /** Who writes, as of now ({@link GitFolder#now}). */
    PersonIdent now() {
        return folder.now();
    }

    /**
     * True when the project directory already has a repository: a {@code .git} directory, or a {@code .git}
     * <i>file</i> naming one elsewhere ({@code gitdir: …}), which is what a submodule or a linked worktree
     * has — the template checked out inside the umbrella is one.
     */
    public boolean isRepo() {
        return folder.isRepo();
    }

    /**
     * Whether this checkout's {@code .git} is a file pointing elsewhere — a submodule, or a worktree — whose
     * history somebody else's tooling also writes. Studio takes no automatic version there ({@link Checkpoints}).
     */
    public boolean sharedRepository() {
        return folder.sharedRepository();
    }

    /** The repository's own directory ({@link GitFolder#gitDir}); null when there is none. */
    Path gitDir() {
        return folder.gitDir();
    }

    /**
     * Initializes a repo (with a {@code .gitignore}) and makes the initial commit. No-op if a repo already
     * exists — call this on project creation, or lazily before any other VCS action for a pre-existing project.
     */
    public void init() throws IOException {
        init(VersionOrigin.CREATE, "Initial commit");
    }

    /** {@link #init()}, its first commit {@code label} of {@code origin}: a plugin's folder of this PC moved in. */
    public void init(VersionOrigin origin, String label) throws IOException {
        folder.init(origin, label);
    }

    /** Lazily initializes the repo (migrating an older project) if it isn't one yet. */
    public void ensureInitialized() throws IOException {
        folder.ensureInitialized();
    }

    /**
     * Stages every change (additions, modifications, deletions of tracked files) and commits it as a version
     * of {@code origin}, the label its first line. Returns the new commit's short SHA, or {@code null} when
     * there was nothing to commit — a checkpoint of an unchanged tree is noise in the one list a user reads.
     *
     * <p>The only way Studio writes a version: the trailer is what lets the timeline tell a user's milestone
     * from the snapshots taken on their behalf ({@code docs/refactor/39-versions.md} §3).
     */
    public String checkpoint(VersionOrigin origin, String label) throws IOException {
        return versions.checkpoint(origin, label);
    }

    /**
     * Commits only {@code paths} (relative to the project), as a version of {@code origin}: whatever else changed or is
     * untracked stays out, so a publish never tags a stray file (2026-11-21). Returns the new commit's short SHA, or
     * {@code null} when none of them changed.
     */
    public String checkpointOnly(VersionOrigin origin, String label, Collection<String> paths) throws IOException {
        return versions.checkpointOnly(origin, label, paths);
    }

    /** The full SHA of the commit checked out, or empty with no repository or no commit: a cheap "did it move?". */
    public String head() throws IOException {
        return folder.head();
    }

    /** Newest-first commit history, each annotated with its name (if given one) and any tags pointing at it. */
    public List<CommitInfo> history() throws IOException {
        return versions.history();
    }

    /**
     * Names the version {@code sha} without rewriting it: the name is a git note under {@link #NAMES}, so an
     * automatic version becomes a milestone wherever it sits in the history, pushed or not. A blank name
     * removes it.
     */
    public void name(String sha, String name) throws IOException {
        versions.name(sha, name);
    }

    /**
     * The paths version {@code sha} changed against its first parent — every file, for the first commit — with
     * how each changed. A rename reads as the new path, modified.
     */
    public SortedMap<String, VcsFileStatus> changes(String sha) throws IOException {
        return versions.changes(sha);
    }

    /** A unified text diff of one path as version {@code sha} changed it, against its first parent. */
    public String diff(String sha, String relativePath) throws IOException {
        return versions.diff(sha, relativePath);
    }

    /**
     * The working tree's changes against {@code HEAD}, bucketed for the VCS panel's changed-files tree. New
     * files added but not committed since {@code init} show up as {@code added}/{@code untracked}. Clean (and
     * empty) when the project has no repo yet.
     */
    public FileStatus status() throws IOException {
        return versions.status();
    }

    /**
     * A unified text diff of one working-tree file against {@code HEAD} (what committing it would record).
     * Returns an empty string when there is no difference, or a placeholder note for a binary change. The
     * path is the POSIX-style project-relative path from {@link #status()}.
     */
    public String diff(String relativePath) throws IOException {
        return versions.diff(relativePath);
    }

    /**
     * Discards a single file's uncommitted changes, restoring it to its {@code HEAD} content ({@code git
     * checkout -- path}). A far narrower undo than {@link #restoreTo}. No-op for an untracked file (there is
     * no committed version to restore — the caller deletes it instead if desired).
     */
    public void discard(String relativePath) throws IOException {
        versions.discard(relativePath);
    }

    /** A private (local-only) tag — never triggers a gallery publish. */
    public void tagPrivate(String name) throws IOException {
        tags.createTag(name, "Private tag " + name);
    }

    /**
     * A public tag — the caller (publish flow) treats this as the signal to submit to the gallery. The tag
     * itself is an ordinary annotated tag; the "public" distinction is the action taken alongside it.
     */
    public void tagPublic(String name) throws IOException {
        tags.createTag(name, "Public release " + name);
    }

    /**
     * Safely rewinds the project to the state at {@code sha} without losing any history: any pending changes
     * are committed first (snapshot), then a new commit is created on top of the current tip whose content
     * exactly matches {@code sha}. Fully linear and reflog-safe.
     */
    public void restoreTo(String sha) throws IOException {
        restoreTo(sha, VersionOrigin.RESTORE, null);
    }

    /**
     * {@link #restoreTo(String)} with the new commit's origin and label ({@code null}: "Roll back to …"); the tip's
     * full SHA afterwards, which is unchanged when the tree already matched {@code sha}.
     */
    public String restoreTo(String sha, VersionOrigin origin, String label) throws IOException {
        return versions.restoreTo(sha, origin, label);
    }

    // -------------------------------------------------------------------------
    // Updates: a real merge of the author's tag (39 §7)
    // -------------------------------------------------------------------------

    /** Whose side of a conflicting file wins. */
    public enum Side { MINE, THEIRS }

    /**
     * How {@link #mergeTag} left the project.
     *
     * @param conflicts the files both sides changed, to be decided one by one; empty unless {@link #conflicted}
     */
    public record Merge(boolean upToDate, List<String> conflicts) {

        public boolean conflicted() {
            return !conflicts.isEmpty();
        }
    }

    /**
     * Fetches the tag {@code tag} from {@code remote} without moving one this computer already has: a release
     * its author moved after the user took it is refused, with a sentence, and nothing changes.
     */
    public void fetchTag(Remote remote, String tag, String token) throws IOException {
        transfer.fetchTag(remote, tag, token);
    }

    /**
     * Merges the tag {@code tag} into the current branch, <b>without committing</b>: files only one side changed
     * are merged, and a file both changed is left conflicted for {@link #resolve}. The tree must be saved first —
     * the caller takes a version — so {@link #abortMerge} can always put it back. {@link #finishMerge} commits.
     */
    public Merge mergeTag(String tag) throws IOException {
        return merges.mergeTag(tag);
    }

    /**
     * {@link #mergeTag}'s merge, of a branch — {@code refs/heads/…}, or {@code refs/remotes/<remote>/…} for a
     * pull — into the current one: uncommitted, conflicts left for {@link #resolve}, a saved tree required.
     */
    public Merge mergeRef(String ref) throws IOException {
        return merges.mergeRef(ref);
    }

    /** Whether an update is half done — merged, not yet committed or cancelled. */
    public boolean merging() {
        return folder.merging();
    }

    /** The release a half-done update is merging — its tag, else its short SHA; null when none is. */
    public String mergeRelease() throws IOException {
        return merges.mergeRelease();
    }

    /** The files of an update still to decide. */
    public List<String> unresolved() throws IOException {
        return merges.unresolved();
    }

    /**
     * Decides one conflicting file: this computer's side or the author's, whole — a file one side deleted is
     * deleted when that side wins.
     */
    public void resolve(String path, Side side) throws IOException {
        merges.resolve(path, side);
    }

    /** Commits the update as an {@code UPDATE} version once every file is decided; returns its short SHA. */
    public String finishMerge(String label) throws IOException {
        return finishMerge(VersionOrigin.UPDATE, label);
    }

    /** {@link #finishMerge(String)} as a version of {@code origin} — a branch merged in the Dev view is a {@code SAVE}. */
    public String finishMerge(VersionOrigin origin, String label) throws IOException {
        return merges.finishMerge(origin, label);
    }

    /** Cancels a half-done update: the tree is the saved version it started from, and nothing was committed. */
    public void abortMerge() throws IOException {
        merges.abortMerge();
    }

    // -------------------------------------------------------------------------
    // Remotes: My copy and Original (39 §2, §7)
    // -------------------------------------------------------------------------

    /** {@code remote}'s URL, or null when the project has none. */
    public String remoteUrl(Remote remote) {
        return remotes.remoteUrl(remote);
    }

    /** The URL of the remote named {@code name}, or null. */
    public String remoteUrl(String name) {
        return remotes.remoteUrl(name);
    }

    // -------------------------------------------------------------------------
    // Dev view: branches and remotes (39 §9)
    // -------------------------------------------------------------------------

    /** One remote as the Dev view lists it: {@code ahead}/{@code behind} by the last fetch, -1 when never read. */
    public record RemoteInfo(String name, String url, int ahead, int behind) {
    }

    /** The local branches, sorted; the current one among them. A save is one only while it is the current one. */
    public List<String> branches() throws IOException {
        return branches.branches();
    }

    /** A new branch at {@code HEAD}, not switched to. */
    public void createBranch(String name) throws IOException {
        branches.createBranch(name);
    }

    /**
     * Switches to {@code name}. The tree must be saved — the caller takes an {@code AUTO} version first — and
     * no update or merge may be half done. Returns how many files changed: a branch at the same tree, 0, needs no
     * reload (2026-10-25).
     */
    public int switchTo(String name) throws IOException {
        return branches.switchTo(name);
    }

    /**
     * Deletes {@code name}, which must not be the current branch and must be merged: a branch whose versions
     * are nowhere else is refused, since deleting it would lose them ({@code 39} §10, nothing rewrites history).
     */
    public void deleteBranch(String name) throws IOException {
        branches.deleteBranch(name);
    }

    /**
     * Whether {@code branch} on {@code remote}, by the last fetch, is merged into {@code into} there: every one
     * of its versions is in {@code into}. False when either is unknown here.
     */
    public boolean mergedInto(Remote remote, String branch, String into) {
        return branches.mergedInto(remote, branch, into);
    }

    /** Whether {@code branch} on {@code remote}, by the last fetch, is in the local branch {@code local}. */
    public boolean inLocal(Remote remote, String branch, String local) {
        return branches.inLocal(remote, branch, local);
    }

    /**
     * Deletes {@code branch} on {@code remote} (a push of {@code :refs/heads/<branch>}) and its remote-tracking
     * ref here. The push is leased on the commit this computer last saw there: a branch that moved since (a push
     * from another computer) is refused, never erased. A branch already gone there passes. The token goes only
     * to GitHub ({@link GitTransfer#tokenFor}).
     */
    public void deleteRemoteBranch(Remote remote, String branch, String token) throws IOException {
        transfer.deleteRemoteBranch(remote, branch, token);
    }

    /** Every remote, with how the current branch stands against its branch of the same name there. */
    public List<RemoteInfo> remotes() throws IOException {
        return remotes.remotes();
    }

    /**
     * Adds a remote. HTTPS only, and never an address carrying a user or a token: what goes in
     * {@code .git/config} is readable by every tool on the machine ({@code 39} §10).
     */
    public void addRemote(String name, String url) throws IOException {
        remotes.addRemote(name, url);
    }

    /** Points {@code remote} at {@code url} — add or set-url in one call. The URL is plain HTTPS, no token. */
    public void setRemote(Remote remote, String url) throws IOException {
        remotes.setRemote(remote, url);
    }

    /** Whether this computer has seen {@code branch} on {@code remote}: its remote-tracking ref exists. */
    public boolean hasRemoteBranch(Remote remote, String branch) {
        return branches.hasRemoteBranch(remote, branch);
    }

    /**
     * Removes {@code remote} and its remote-tracking refs; nothing when there is none. The tags it brought stay:
     * they are commits of this history. <i>Stop following</i> ({@code 39} §7, 2026-10-23).
     */
    public void removeRemote(Remote remote) throws IOException {
        remotes.removeRemote(remote);
    }

    /**
     * Gives Studio's two remotes their git names, once, on open ({@code 39} §2, 2026-10-25): {@code mine}
     * becomes {@link Remote#MINE} ({@code origin}) and {@code original} becomes {@link Remote#ORIGINAL}
     * ({@code upstream}), each with its remote-tracking refs and the branches that track it. Returns whether
     * anything changed. Idempotent.
     *
     * <p>A name already taken by a remote of the user's own is the same repository — the old backup
     * {@code origin}, a submodule's {@code origin} Studio had copied — and then the old remote is dropped, or
     * another one, renamed {@code old-<name>} first. An {@code origin} Studio never renamed (the old backup, a
     * hand-made clone) was always the user's own, which is what {@code origin} means now; its SSH address is
     * written as the same repository's HTTPS one, since Studio pushes with the GitHub token
     * ({@link GitTransfer#tokenFor}).
     *
     * <p>A checkout whose {@code .git} is a <i>file</i> — a submodule, a linked worktree — belongs to another
     * tool as well, which pushes to {@code origin} by name (the umbrella's release does): there no taken name is
     * moved and no address rewritten.
     */
    public boolean adoptRemoteNames() throws IOException {
        return remotes.adoptRemoteNames();
    }

    /** The current branch's name ({@code main}, for a project Studio cloned). */
    public String branch() throws IOException {
        return branches.branch();
    }

    /**
     * Pushes the current branch to {@code remoteBranch} on {@code remote}, with the tags and the version names
     * ({@link #NAMES}). Returns the local branch. Never forced: a remote that has versions this computer lacks
     * is reported, not overwritten.
     *
     * @param token a GitHub token, handed to JGit for this call only
     */
    public String push(Remote remote, String remoteBranch, String token) throws IOException {
        return push(remote, remoteBranch, token, false);
    }

    /**
     * {@link #push(Remote, String, String)}, forced when {@code force} — for a {@code suggest/…} branch only,
     * the one branch Studio moves ({@code 39} §10): a suggestion made again replaces the last one.
     */
    public String push(Remote remote, String remoteBranch, String token, boolean force) throws IOException {
        return transfer.push(remote, remoteBranch, token, force);
    }

    /**
     * The push itself, to any remote by name — the Dev view's <i>Push</i> names one of its own. The token goes
     * only to GitHub ({@link GitTransfer#tokenFor}); another host is pushed to without one.
     */
    public String push(String remoteName, String remoteBranch, String token, boolean force) throws IOException {
        return transfer.push(remoteName, remoteBranch, token, force);
    }

    /** Fetches {@code remote}'s branches and tags. {@code token} may be null for a public repository. */
    public void fetch(Remote remote, String token) throws IOException {
        fetch(remote.id(), token);
    }

    /** Fetches the remote named {@code remoteName}; the token goes only to GitHub ({@link GitTransfer#tokenFor}). */
    public void fetch(String remoteName, String token) throws IOException {
        transfer.fetch(remoteName, token);
    }

    /**
     * How many versions of this computer's branch {@code remote}'s {@code remoteBranch} lacks, by the last
     * fetch or push — no network. -1 when nothing was ever pushed there.
     */
    public int notIn(Remote remote, String remoteBranch) throws IOException {
        return branches.notIn(remote, remoteBranch);
    }

    /** The tag names {@code remote} has, asked over the network; {@code token} may be null. */
    public List<String> remoteTags(Remote remote, String token) throws IOException {
        return transfer.remoteTags(remote, token);
    }

    /**
     * Clones {@code url} into {@code dest} at {@code tag}: the remote is {@link Remote#ORIGINAL}, and a branch
     * {@code main} starts at the tag and is checked out — an installed bot is a real clone of its author's
     * repository ({@code 39} §7).
     */
    public static void cloneAt(String url, String tag, Path dest, String token) throws IOException {
        cloneAt(url, tag, "", dest, token);
    }

    /** The refusal of a release whose tag is no longer at the commit the catalog approved it at. */
    public static IOException changedSinceApproved(String tag) {
        return GitTransfer.changedSinceApproved(tag);
    }

    /**
     * {@link #cloneAt(String, String, Path, String)}, refused unless the tag is at {@code commit}, the one the
     * catalog approved it at (2026-10-24): a tag moved since holds code no check passed. A blank {@code commit}
     * checks nothing.
     */
    public static void cloneAt(String url, String tag, String commit, Path dest, String token) throws IOException {
        GitTransfer.cloneAt(url, tag, commit, dest, token);
    }

    /**
     * Joins a project installed from a zip to its author's history, once ({@code 39} §7): {@link Remote#ORIGINAL}
     * is set to {@code url}, the tag is fetched, and a merge commit whose parents are {@code HEAD} and the tag's
     * commit is written <b>with the user's tree unchanged</b> — the tag becomes the merge base an update needs,
     * and no edit moves. Returns false when {@code HEAD} already contains the tag (a clone, or attached before).
     */
    public boolean attach(String url, String tag, String label, String token) throws IOException {
        return transfer.attach(url, tag, label, token);
    }

    /**
     * Before the first git publish into a repository that already has a history this project does not share
     * — one Studio published through the Git Data API before 2026-09-25, whose commits were built on GitHub
     * from a snapshot — fetches {@code remoteBranch} of {@code remote} and records a merge of it with this
     * computer's tree unchanged, so the push that follows is a fast-forward and nothing on GitHub is
     * overwritten. Returns false, changing nothing, when the branch does not exist there yet or already shares
     * history with {@code HEAD}: a remote that is merely ahead is refused by the push, as it should be.
     */
    public boolean joinUnrelated(Remote remote, String remoteBranch, String label, String token) throws IOException {
        return transfer.joinUnrelated(remote, remoteBranch, label, token);
    }

    /**
     * The nearest tag {@code HEAD} contains, as {@code git describe --tags --abbrev=0} says it: for a bot started
     * from someone else's, the release it was installed at or last updated to — an update's merge contains the
     * release it merged, which is nearer than every older one. {@code ""} when no tag is reachable or the project
     * has no history.
     */
    public String releaseTag() {
        return tags.releaseTag();
    }

    /**
     * Marks the tag {@code name} as a release of the user's fork rather than of the original (2026-10-23), so
     * {@link #releaseTag} keeps naming the author's release the project is at. Idempotent.
     */
    public void markOwnRelease(String name) throws IOException {
        tags.markOwnRelease(name);
    }

    /** Whether this project has the tag {@code name}. */
    public boolean hasTag(String name) {
        return tags.hasTag(name);
    }

    /** Whether {@code name} is one of the user's fork's own releases ({@link #markOwnRelease}). */
    public boolean isOwnRelease(String name) {
        return tags.isOwnRelease(name);
    }

    /** The commit {@code HEAD} is, or empty before the first version. */
    public Optional<String> headCommit() {
        return folder.headCommit();
    }

    /** The commit the tag {@code name} names, or empty when there is no such tag. */
    public Optional<String> tagCommit(String name) {
        return tags.tagCommit(name);
    }

    /** The tags whose commit is {@code HEAD}'s. */
    public List<String> tagsAtHead() {
        return tags.tagsAtHead();
    }

    /** The tags whose commit {@code HEAD} contains, {@code git tag --merged HEAD}. */
    public List<String> tagsMerged() {
        return tags.tagsMerged();
    }

    /** The nearest tag {@code HEAD} contains, any tag, as {@code git describe --tags --abbrev=0}; empty when none. */
    public Optional<String> nearestTag() {
        return tags.nearestTag();
    }

    /**
     * The bytes {@code HEAD}'s commit holds for each file {@code wanted} accepts, by path: the blobs themselves,
     * never the working tree, so no line-ending conversion or filter changes them (a {@code .bmk}'s sources,
     * 2026-10-25). A link or a submodule {@code wanted} accepts is refused: the caller wants files.
     *
     * @throws IOException with the path, for a link or a submodule; or when there is no commit
     */
    public Map<String, byte[]> filesAtHead(Predicate<String> wanted) throws IOException {
        return versions.filesAtHead(wanted);
    }

    /**
     * An annotated tag {@code name} at {@code HEAD}, unless the tag exists already — a publish resumed after
     * its tag was made keeps the commit it named then. Returns the commit the tag names.
     */
    public String tagHead(String name, String message) throws IOException {
        return tags.tagHead(name, message);
    }

    /** The repository, for this package's readers ({@link VersionReader}); the caller closes it. */
    public Git open() throws IOException {
        return folder.open();
    }
}
