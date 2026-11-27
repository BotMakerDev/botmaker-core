// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.server;

import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.pilot.transport.TransportKind;
import com.botmaker.core.plugin.PluginWrite;
import com.botmaker.core.run.claims.SeveralAsk;
import com.botmaker.core.run.trial.TrialAsk;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import javax.lang.model.SourceVersion;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a client asks the core over its {@link Channel}, one message each, written by {@code CoreJson}. The core answers
 * each with a {@link Reply.Answer} of the same {@link #id}, in the order asked; what follows from it reaches every client
 * attached to the project as {@link Reply.Told}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "ask")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Request.Open.class, name = "open"),
        @JsonSubTypes.Type(value = Request.Show.class, name = "show"),
        @JsonSubTypes.Type(value = Request.Apply.class, name = "apply"),
        @JsonSubTypes.Type(value = Request.UserEdit.class, name = "userEdit"),
        @JsonSubTypes.Type(value = Request.Step.class, name = "step"),
        @JsonSubTypes.Type(value = Request.Restore.class, name = "restore"),
        @JsonSubTypes.Type(value = Request.Tools.class, name = "tools"),
        @JsonSubTypes.Type(value = Request.Called.class, name = "called"),
        @JsonSubTypes.Type(value = Request.PluginWriting.class, name = "pluginWriting"),
        @JsonSubTypes.Type(value = Request.Batch.class, name = "batch"),
        @JsonSubTypes.Type(value = Request.Together.class, name = "together"),
        @JsonSubTypes.Type(value = Request.Reload.class, name = "reload"),
        @JsonSubTypes.Type(value = Request.DevReload.class, name = "devReload"),
        @JsonSubTypes.Type(value = Request.Compile.class, name = "compile"),
        @JsonSubTypes.Type(value = Request.Run.class, name = "run"),
        @JsonSubTypes.Type(value = Request.RunEntry.class, name = "runEntry"),
        @JsonSubTypes.Type(value = Request.RunSeveral.class, name = "runSeveral"),
        @JsonSubTypes.Type(value = Request.Try.class, name = "try"),
        @JsonSubTypes.Type(value = Request.Debug.class, name = "debug"),
        @JsonSubTypes.Type(value = Request.Stop.class, name = "stop"),
        @JsonSubTypes.Type(value = Request.Answer.class, name = "answer"),
        @JsonSubTypes.Type(value = Request.Debugging.class, name = "debugging"),
        @JsonSubTypes.Type(value = Request.Close.class, name = "close"),
        @JsonSubTypes.Type(value = Request.WatchCalls.class, name = "watchCalls"),
        @JsonSubTypes.Type(value = Request.PilotWatch.class, name = "pilotWatch"),
        @JsonSubTypes.Type(value = Request.PilotStart.class, name = "pilotStart"),
        @JsonSubTypes.Type(value = Request.PilotStop.class, name = "pilotStop"),
        @JsonSubTypes.Type(value = Request.PilotReset.class, name = "pilotReset"),
        @JsonSubTypes.Type(value = Request.PilotDisconnect.class, name = "pilotDisconnect"),
        @JsonSubTypes.Type(value = Request.PilotBackgroundStart.class, name = "pilotBackgroundStart"),
        @JsonSubTypes.Type(value = Request.PilotBackgroundStop.class, name = "pilotBackgroundStop")})
public sealed interface Request {

    /** Which ask this is; the answer carries it back. */
    long id();

    /**
     * What steers a run already going. It is done at once, on a thread of its own, never behind an ask the project's
     * thread is busy with: a stop waits for no library resolve while the bot goes on clicking.
     */
    sealed interface Control extends Request {
    }

    /** Compiles the bot; how it goes is told. */
    record Compile(long id) implements Request {
    }

    /** Compiles and runs the bot; accepted once it started to, how it goes told as {@code CoreEvent}s. */
    record Run(long id) implements Request {
    }

    /**
     * Compiles once and starts the runs {@code ask} asks for, each dealt a row of its pools, or only counts them;
     * answered once they started, what started (or could) told first as {@code CoreEvent.RunsStarted}, or refused
     * saying why none could.
     *
     * @param token what the told event names this start by: unique across clients, whose ids are each their own
     */
    record RunSeveral(long id, SeveralAsk ask, String token) implements Request {
        public RunSeveral {
            if (ask == null) throw new IllegalArgumentException("a start of several runs says what it asks");
            if (token == null || !token.matches("[A-Za-z0-9-]{1,64}")) {
                throw new IllegalArgumentException("a start of several runs has a token of letters, digits and dashes");
            }
        }
    }

    /**
     * As {@link Run}, with a plugin's declared run {@code title} as the JVM's main, {@code mainClass}, and its
     * {@code arguments} as {@code RunEntry.arguments} checked them ({@code ""}: none).
     */
    record RunEntry(long id, String title, String mainClass, String arguments) implements Request {
        public RunEntry {
            if (mainClass == null || !SourceVersion.isName(mainClass)) {
                throw new IllegalArgumentException("a declared run names its class");
            }
            arguments = arguments == null ? "" : arguments;
            // RunEntry.arguments' text, Base64: nothing else reaches the run's command line.
            if (!arguments.matches("[A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("a run's arguments are the text RunEntry.arguments gives");
            }
        }

        /** A start with no arguments, as a toolbar button or a plugin's window asks it. */
        public RunEntry(long id, String title, String mainClass) {
            this(id, title, mainClass, "");
        }
    }

    /**
     * ▶ Try: the core plans {@code trial} on its own tree, writes its caller beside the bot (in none of the bot's
     * sources), compiles both and runs the caller (plan phase 10c). Refused, saying why, when it cannot be tried.
     */
    record Try(long id, TrialAsk trial) implements Request {
        public Try {
            if (trial == null) throw new IllegalArgumentException("a try says what it tries");
        }
    }

    /** A plugin's write of the bot's values, which a client's copy of the plugin asked for (plan phase 10c). */
    record PluginWriting(long id, PluginWrite write) implements Request {
        public PluginWriting {
            if (write == null) throw new IllegalArgumentException("a plugin's write carries its write");
        }
    }

    /**
     * A plugin's batch opens ({@code open}, under {@code label}) or ends: what the client's plugin writes in between is
     * one step of the history. A client that leaves ends the batches it opened.
     */
    record Batch(long id, String label, boolean open) implements Request {
    }

    /**
     * A plugin's together (2026-11-21, {@code History.together}): {@code writes} made in order as one step named
     * {@code label}, part of the batch open if one is, or none of them; answered with the first refusal.
     */
    record Together(long id, String label, List<PluginWrite> writes) implements Request {
        public Together {
            writes = writes == null ? List.of() : List.copyOf(writes);
        }
    }

    /**
     * Starts a debug session, or Follow when {@code follow}. {@code breakpoints} holds the node ids the user marked in
     * each file, by its path below the source root: they are the user's, kept by the client that drew them.
     */
    record Debug(long id, boolean follow, Map<String, Set<String>> breakpoints) implements Request {
        public Debug {
            breakpoints = breakpoints == null ? Map.of() : Map.copyOf(breakpoints);
        }
    }

    /** Stops the bot's run. */
    record Stop(long id) implements Control {
    }

    /** The user's answer {@code value} to the running bot's question {@code asked}. */
    record Answer(long id, long asked, String value) implements Control {
    }

    /** Steps, resumes or ends the debug session. */
    record Debugging(long id, Step step) implements Control {
        public Debugging {
            if (step == null) throw new IllegalArgumentException("a debug control says which");
        }

        public enum Step { STEP_OVER, CONTINUE, STOP }
    }

    /** Attaches the client to the project in {@code project}, an absolute directory, opening it when no client has. */
    record Open(long id, String project) implements Request {
        public Open {
            if (project == null || project.isBlank()) throw new IllegalArgumentException("an open names its project");
        }
    }

    /** Makes {@code file}, by its path below the bot's source root, the project's open file. */
    record Show(long id, String file) implements Request {
        public Show {
            if (file == null || file.isBlank()) throw new IllegalArgumentException("a show names its file");
        }
    }

    /** An assistant's or a tool's edits, judged by the request's guard. */
    record Apply(long id, EditRequest request) implements Request {
        public Apply {
            if (request == null) throw new IllegalArgumentException("an apply carries its request");
        }
    }

    /** One edit the user made by hand on the open file. */
    record UserEdit(long id, Edit edit) implements Request {
        public UserEdit {
            if (edit == null) throw new IllegalArgumentException("a user's edit carries its edit");
        }
    }

    /**
     * Puts {@code function} back in {@code file}, by its path below the source root, as the newest saved version had
     * it before the refactor whose open {@code @Refactor} entries are {@code entries} (Review's Undo). The core reads
     * the versions and writes the function; refused, saying why, when it cannot. One step of the history.
     */
    record Restore(long id, String file, String function, List<String> entries) implements Request {
        public Restore {
            if (file == null || file.isBlank() || function == null || function.isBlank()) {
                throw new IllegalArgumentException("a restore names its file and function");
            }
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** An undo or a redo of the project's history; refused when there is no step that way. */
    record Step(long id, HistoryStep step) implements Request {
        public Step {
            if (step == null) throw new IllegalArgumentException("a step says which way");
        }
    }

    /**
     * The MCP tools this client serves through the core from now on, replacing what it registered before (plan phase
     * 10d): each an MCP {@code Tool} as its JSON. The core serves them beside its own and forwards each call as a
     * {@link Reply.Call}. {@code admits}: the client has a screen whose questions the core's own tools wait for, so the
     * core asks it before each of them ({@link Reply.Call.Kind#ADMIT}). Answered at once; a channel that ends takes
     * its tools with it. Needs no open project.
     */
    record Tools(long id, List<String> tools, boolean admits) implements Request {
        public Tools {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /** The client's answer to the core's {@link Reply.Call} {@code call}: an MCP {@code CallToolResult} as its JSON. */
    record Called(long id, long call, String result) implements Request {
        public Called {
            if (result == null || result.isBlank()) throw new IllegalArgumentException("a call's answer carries its result");
        }
    }

    /**
     * The project's libraries changed: the core resolves them again, puts the contract's dependency right, binds their
     * plugins again and writes a holder a new plugin brings; it tells {@code LibrariesResolved} before it answers.
     */
    record Reload(long id) implements Request {
    }

    /**
     * A client's plugin {@code groupId:artifactId} restarts its own run {@code entry}, by title, on its rebuilt jar
     * ({@code StudioServices.devReload}, 2026-11-20): the core's copy of the plugin reloads, as when it asks itself.
     * Accepted once the reload started; refused, nothing stopped, with why.
     */
    record DevReload(long id, String groupId, String artifactId, String entry) implements Request {
    }

    /**
     * Detaches the client from its project; the last client to leave closes it. {@code keepRun}: the bot running now
     * goes on, and the project stays open until it ends (plan phase 11, the user's "keep it running"); else the last
     * client to leave ends it.
     */
    record Close(long id, boolean keepRun) implements Request {
    }

    /**
     * Tells this client every MCP call the core serves from now on, the kept ones first, as
     * {@code CoreEvent.ToolCallStarted} and {@code ToolCallEnded} (the Assistant window, 2026-11-11), until its channel
     * ends. Answered at once; needs no open project.
     */
    record WatchCalls(long id) implements Request {
    }

    /**
     * What a client asks of the Remote Pilot, which the core runs (2026-11-27). Answered by the core itself, never by
     * a project's thread, and the pilot's state reaches only a channel that sent {@link PilotWatch}: it carries the
     * pairing token, so it goes through no event, no dashboard and no call log.
     */
    sealed interface ForPilot extends Request {
    }

    /** Tells this client the pilot's state now and at each change ({@link Reply.Pilot}), until its channel ends. */
    record PilotWatch(long id) implements ForPilot {
    }

    /**
     * Brings the pilot up over {@code transport}, or over the remembered one when null; needs an open project and is
     * answered once the start settled, on or off with why.
     */
    record PilotStart(long id, TransportKind transport) implements ForPilot {
    }

    /** Takes the pilot down. */
    record PilotStop(long id) implements ForPilot {
    }

    /** Revokes the pairing token: every phone has to scan again. */
    record PilotReset(long id) implements ForPilot {
    }

    /**
     * Starts the open bot's game in a private display, which the pilot then shows and drives (Background mode).
     * Answered at once, accepted when the start is under way or refused with why not; the outcome follows in the
     * pilot's state.
     */
    record PilotBackgroundStart(long id) implements ForPilot {
    }

    /** Takes the private display down, returning the pilot to the real desktop. */
    record PilotBackgroundStop(long id) implements ForPilot {
    }

    /** Disconnects the phone {@code phone} of the pilot's state; it may pair again. */
    record PilotDisconnect(long id, String phone) implements ForPilot {
        public PilotDisconnect {
            if (phone == null || phone.isBlank()) throw new IllegalArgumentException("a disconnect names its phone");
        }
    }
}
