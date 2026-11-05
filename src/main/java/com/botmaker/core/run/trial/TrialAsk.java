package com.botmaker.core.run.trial;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.plugin.ProbeCalls;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.index.BotIndex;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Statement;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What a client asks to try (plan phase 10c): a statement of the open file by its {@code BlockId}, with the values the
 * user chose for the earlier locals it reads, or a method run on its own. The core plans it again from its own tree and
 * writes the caller itself ({@link #prepare}), so no Java text crosses: a value given crosses as a {@link JavaValue},
 * read back as one expression.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "trial")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TrialAsk.OneStatement.class, name = "statement"),
        @JsonSubTypes.Type(value = TrialAsk.OnItsOwn.class, name = "method")})
public sealed interface TrialAsk {

    /**
     * The statement {@code node} of the open {@code file}; each local it needs is {@code given} a value, or
     * {@code computed} from its own initializer.
     */
    record OneStatement(String file, String node, Map<String, JavaValue> given, Set<String> computed)
            implements TrialAsk {
        public OneStatement {
            if (file == null || node == null) throw new IllegalArgumentException("a try names its statement");
            given = given == null ? Map.of() : Map.copyOf(given);
            computed = computed == null ? Set.of() : Set.copyOf(computed);
        }
    }

    /** {@code className.method()} with no arguments, its package given apart. */
    record OnItsOwn(String packageName, String className, String method, boolean valued) implements TrialAsk {
        public OnItsOwn {
            if (className == null || method == null) throw new IllegalArgumentException("a run names its method");
            packageName = packageName == null ? "" : packageName;
            // The names become the caller's path and its Java: only names.
            if (!SourceVersion.isName(className) || !SourceVersion.isIdentifier(method)
                    || !(packageName.isEmpty() || SourceVersion.isName(packageName))) {
                throw new IllegalArgumentException("a run names a class and a method by their Java names");
            }
        }
    }

    /** A try's caller, written, and what is tried in the user's words. */
    record Prepared(TrialCaller.Source caller, String label) {
    }

    /** The values an earlier debug pause left: the client sends what it chose, so the core holds none. */
    TrialPlan.LastRun NO_LAST_RUN = (className, method, local, typeName) -> Optional.empty();

    /**
     * {@code ask} planned on the open project and its caller written.
     *
     * @throws IllegalArgumentException with the sentence why not: no plugin offers a try, the file changed, the plan
     *                                  refused, a local has no value
     */
    static Prepared prepare(TrialAsk ask, ProjectState state, Supplier<BotIndex> index) {
        Method entry = TrialCaller.entry().orElseThrow(() -> new IllegalArgumentException(
                "None of this bot's plugins offers a way to try one statement, so it can only run whole."));
        Map<String, TrialCaller.Value> values = new LinkedHashMap<>();
        TrialPlan.Plan plan = switch (ask) {
            case OnItsOwn call -> TrialPlan.call(call.packageName(), call.className(), call.method(), call.valued());
            case OneStatement one -> {
                one.given().forEach((name, value) -> values.put(name, new TrialCaller.Given(value)));
                one.computed().forEach(name -> values.put(name, new TrialCaller.Computed()));
                yield planned(one, state);
            }
        };
        return new Prepared(TrialCaller.write(plan, values, entry, TrialCaller.holders(index.get())), plan.label());
    }

    private static TrialPlan.Plan planned(OneStatement one, ProjectState state) {
        ProjectFile file = state.getActiveFile();
        CompilationUnit unit = state.getCompilationUnit().orElse(null);
        if (file == null || unit == null || state.activeFileName().filter(one.file()::equals).isEmpty()) {
            throw new IllegalArgumentException("The file changed since this block was drawn. Try again once it has "
                    + "caught up.");
        }
        ASTNode node = BlockId.find(unit, one.node()).orElse(null);
        if (!(node instanceof Statement statement)) {
            throw new IllegalArgumentException("The file changed since this block was drawn. Try again once it has "
                    + "caught up.");
        }
        ValueGrammar grammar = PluginHost.grammar();
        List<ProbeCalls.Declared> probes = ProbeCalls.declared(PluginHost.overlayParts());
        TrialPlan.Result result = TrialPlan.plan(statement, file.getContent(),
                binding -> ProbeCalls.readsOnly(binding, probes), text -> grammar.valueOfAny(text).isPresent(),
                NO_LAST_RUN);
        return switch (result) {
            case TrialPlan.Refused refused -> throw new IllegalArgumentException(refused.reason());
            case TrialPlan.Planned planned -> planned.plan();
        };
    }
}
