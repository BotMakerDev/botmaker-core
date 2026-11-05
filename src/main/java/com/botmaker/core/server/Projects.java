package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.EditResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The project this core has open and the channels attached to it. One project at a time: the plugin host binds one
 * project's plugins per process, so a client asking for another while one is open is refused until its clients leave.
 *
 * <p>Opening, leaving and a channel's end run one after another on one thread of their own, since resolving a
 * project's libraries can take minutes: a client that leaves while its project opens is let go once it has opened, and
 * a close waits for the asks the client sent before it. The other asks go to the project's own thread.
 */
public final class Projects implements AutoCloseable {

    private static final ObjectMapper LENIENT = new ObjectMapper();

    private final OpenProject.Libraries libraries;
    private final Map<Channel, OpenProject> attached = new ConcurrentHashMap<>();
    private final ExecutorService opener = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "core-open");
        thread.setDaemon(true);
        return thread;
    });
    /** Set and read on the opener's thread, and by {@link #close}. */
    private volatile OpenProject open;

    public Projects(OpenProject.Libraries libraries) {
        this.libraries = libraries;
    }

    /** Serves {@code channel}'s requests from now on; what {@link CoreServer.Hooks#opened} is given. */
    public void attach(Channel channel) {
        channel.onMessage(text -> received(channel, text));
        channel.onClose(() -> inTurn(() -> leave(channel)));
    }

    /** Whether a project is open. */
    public boolean hasOpen() {
        return open != null;
    }

    private void received(Channel channel, String text) {
        Request request;
        try {
            request = CoreJson.read(text, Request.class);
        } catch (IllegalArgumentException unreadable) {
            refuseUnreadable(channel, text, unreadable);
            return;
        }
        switch (request) {
            case Request.Open ask -> inTurn(() -> answer(channel, ask.id(), open(channel, ask.project())));
            case Request.Close ask -> inTurn(() -> {
                OpenProject project = attached.get(channel);
                if (project != null) project.drain();
                leave(channel);
                answer(channel, ask.id(), new EditResult.Accepted(List.of()));
            });
            default -> {
                OpenProject project = attached.get(channel);
                if (project == null) {
                    answer(channel, request.id(), new EditResult.Refused("No project is open."));
                } else {
                    project.ask(request).thenAccept(result -> answer(channel, request.id(), result));
                }
            }
        }
    }

    /** A message no request reads as: refused by its id when it has one, so its client does not wait for nothing. */
    private static void refuseUnreadable(Channel channel, String text, IllegalArgumentException why) {
        try {
            JsonNode id = LENIENT.readTree(text).path("id");
            if (id.canConvertToLong()) {
                answer(channel, id.asLong(), new EditResult.Refused("The core could not read that: " + why.getMessage()));
                return;
            }
        } catch (IOException notJson) {
            // Nothing to answer by.
        }
        System.err.println("A client sent what is no request: " + why.getMessage());
    }

    /** Runs {@code work} after every open, leave and close before it; nothing once the core is ending. */
    private void inTurn(Runnable work) {
        try {
            opener.execute(work);
        } catch (RejectedExecutionException ending) {
            // The core is closing: every project goes with it.
        }
    }

    private EditResult open(Channel channel, String directory) {
        Path dir;
        try {
            dir = Path.of(directory).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return new EditResult.Refused(directory + " is not a folder.");
        }
        if (open != null && !open.directory().equals(dir)) {
            return new EditResult.Refused(open.directory().getFileName()
                    + " is open in BotMaker's core; close it before opening " + dir.getFileName() + ".");
        }
        boolean first = open == null;
        if (first) {
            try {
                open = OpenProject.open(dir, libraries);
            } catch (IOException | RuntimeException e) {
                return new EditResult.Refused(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        OpenProject was = attached.put(channel, open);
        if (was != null && was != open) closeIfLeft(was, channel);
        open.attach(channel);
        // With a client on it: a value the fit moved or reset is said once, and that client is who must hear it.
        if (first) open.fitParams();
        // A channel that ended while the project opened heard nothing and never will: let it go now.
        if (!channel.isOpen()) leave(channel);
        return new EditResult.Accepted(List.of());
    }

    private void leave(Channel channel) {
        OpenProject project = attached.remove(channel);
        if (project != null) closeIfLeft(project, channel);
    }

    private void closeIfLeft(OpenProject project, Channel channel) {
        if (!project.detach(channel)) return;
        if (project == open) open = null;
        try {
            project.close();
        } catch (IOException e) {
            System.err.println("Couldn't let " + project.directory().getFileName() + " go: " + e.getMessage());
        }
    }

    private static void answer(Channel channel, long id, EditResult result) {
        channel.send(CoreJson.write(new Reply.Answer(id, result)));
    }

    /** Closes the open project, whoever is attached; the core is ending. */
    @Override
    public void close() {
        opener.shutdownNow();
        OpenProject project = open;
        open = null;
        attached.clear();
        if (project == null) return;
        try {
            project.close();
        } catch (IOException e) {
            System.err.println("Couldn't let " + project.directory().getFileName() + " go: " + e.getMessage());
        }
    }
}
