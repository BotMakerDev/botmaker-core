package com.botmaker.core.server;

import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.plugin.PluginWrite;
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
        @JsonSubTypes.Type(value = Request.Reload.class, name = "reload"),
        @JsonSubTypes.Type(value = Request.Compile.class, name = "compile"),
        @JsonSubTypes.Type(value = Request.Run.class, name = "run"),
        @JsonSubTypes.Type(value = Request.RunEntry.class, name = "runEntry"),
        @JsonSubTypes.Type(value = Request.Try.class, name = "try"),
        @JsonSubTypes.Type(value = Request.Debug.class, name = "debug"),
        @JsonSubTypes.Type(value = Request.Stop.class, name = "stop"),
        @JsonSubTypes.Type(value = Request.Answer.class, name = "answer"),
        @JsonSubTypes.Type(value = Request.Debugging.class, name = "debugging"),
        @JsonSubTypes.Type(value = Request.Close.class, name = "close")})
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

    /** As {@link Run}, with a plugin's declared run {@code title} as the JVM's main, {@code mainClass}. */
    record RunEntry(long id, String title, String mainClass) implements Request {
        public RunEntry {
            if (mainClass == null || !SourceVersion.isName(mainClass)) {
                throw new IllegalArgumentException("a declared run names its class");
            }
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
     * Detaches the client from its project; the last client to leave closes it. {@code keepRun}: the bot running now
     * goes on, and the project stays open until it ends (plan phase 11, the user's "keep it running"); else the last
     * client to leave ends it.
     */
    record Close(long id, boolean keepRun) implements Request {
    }
}
