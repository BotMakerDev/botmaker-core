// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The tags: making one, and which release the project is at. Each public method is documented on {@link ProjectVcs}. */
final class GitTags {

    /**
     * Where a fork's own release tags are marked: a ref per tag, named like it, beside it. Local only — never
     * pushed — so the mark says "made on this computer for the user's fork" and nothing more.
     */
    static final String OWN_RELEASES = "refs/botmaker/own-releases/";

    private final GitFolder folder;

    GitTags(GitFolder folder) {
        this.folder = folder;
    }

    void createTag(String name, String message) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Tag name must not be empty.");
        folder.ensureInitialized();
        try (Git git = folder.open()) {
            git.tag().setName(name.trim()).setMessage(message).setTagger(folder.now()).call();
        } catch (Exception e) {
            throw new IOException("Tag failed: " + e.getMessage(), e);
        }
    }

    String tagHead(String name, String message) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Tag name must not be empty.");
        folder.ensureInitialized();
        try (Git git = folder.open()) {
            ObjectId had = git.getRepository().resolve("refs/tags/" + name.trim() + "^{commit}");
            if (had != null) return had.name();
            git.tag().setName(name.trim()).setMessage(message).setTagger(folder.now()).call();
            return git.getRepository().resolve("refs/tags/" + name.trim() + "^{commit}").name();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Tag failed: " + e.getMessage(), e);
        }
    }

    String releaseTag() {
        if (!folder.isRepo()) return "";
        try (Git git = folder.open()) {
            Repository repo = git.getRepository();
            // A fork's own releases (markOwnRelease) are the user's, not the original's: an update is offered
            // against the author's release the fork is at, never against the fork's own number.
            List<String> theirs = new ArrayList<>();
            for (Ref tag : repo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS)) {
                String name = tag.getName().substring(Constants.R_TAGS.length());
                if (repo.exactRef(OWN_RELEASES + name) == null) theirs.add(name);
            }
            if (theirs.isEmpty()) return "";
            String described = git.describe().setTags(true).setMatch(theirs.toArray(String[]::new)).call();
            return described == null ? "" : described.replaceFirst("-\\d+-g[0-9a-f]+$", "");
        } catch (Exception e) {
            return "";
        }
    }

    void markOwnRelease(String name) throws IOException {
        try (Git git = folder.open()) {
            Repository repo = git.getRepository();
            ObjectId tag = repo.resolve(Constants.R_TAGS + name.trim());
            if (tag == null) throw new IOException("No tag " + name + " to mark.");
            RefUpdate mark = repo.updateRef(OWN_RELEASES + name.trim());
            mark.setNewObjectId(tag);
            mark.setForceUpdate(true);
            mark.update();
        }
    }

    boolean hasTag(String name) {
        if (!folder.isRepo()) return false;
        try (Git git = folder.open()) {
            return git.getRepository().exactRef(Constants.R_TAGS + name.trim()) != null;
        } catch (IOException e) {
            return false;
        }
    }

    boolean isOwnRelease(String name) {
        if (!folder.isRepo()) return false;
        try (Git git = folder.open()) {
            return git.getRepository().exactRef(OWN_RELEASES + name.trim()) != null;
        } catch (IOException e) {
            return false;
        }
    }

    Optional<String> tagCommit(String name) {
        if (!folder.isRepo()) return Optional.empty();
        try (Git git = folder.open()) {
            return Optional.ofNullable(git.getRepository().resolve(Constants.R_TAGS + name.trim() + "^{commit}"))
                    .map(ObjectId::name);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    List<String> tagsAtHead() {
        Optional<String> head = folder.headCommit();
        return head.isEmpty() ? List.of() : tags().stream().filter(t -> tagCommit(t).equals(head)).toList();
    }

    List<String> tagsMerged() {
        if (folder.headCommit().isEmpty()) return List.of();
        try (Git git = folder.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit head = walk.parseCommit(git.getRepository().resolve(Constants.HEAD));
            List<String> merged = new ArrayList<>();
            for (String tag : tags()) {
                Optional<String> at = tagCommit(tag);
                if (at.isPresent() && walk.isMergedInto(walk.parseCommit(ObjectId.fromString(at.get())), head)) {
                    merged.add(tag);
                }
            }
            return merged;
        } catch (IOException e) {
            return List.of();
        }
    }

    Optional<String> nearestTag() {
        if (folder.headCommit().isEmpty()) return Optional.empty();
        try (Git git = folder.open()) {
            String described = git.describe().setTags(true).call();
            return Optional.ofNullable(described).map(d -> d.replaceFirst("-\\d+-g[0-9a-f]+$", ""));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private List<String> tags() {
        try (Git git = folder.open()) {
            return git.getRepository().getRefDatabase().getRefsByPrefix(Constants.R_TAGS).stream()
                    .map(ref -> ref.getName().substring(Constants.R_TAGS.length())).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Maps each tagged commit SHA (peeled to the commit) to the tag short-names pointing at it. */
    static Map<String, List<String>> tagsByCommit(Git git) throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        Repository repo = git.getRepository();
        for (Ref tag : git.tagList().call()) {
            Ref peeled = repo.getRefDatabase().peel(tag);
            ObjectId id = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : tag.getObjectId();
            String shortName = Repository.shortenRefName(tag.getName());
            map.computeIfAbsent(id.name(), k -> new ArrayList<>()).add(shortName);
        }
        return map;
    }
}
