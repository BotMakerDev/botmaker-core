package com.botmaker.core.edit;

import com.botmaker.core.parser.CodeEditor;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.core.plugin.PaletteCuration;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.managed.ManagedConstants;
import com.botmaker.core.project.managed.ManagedIds;
import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.StatementPlacement;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.guard.CompileGuard;
import com.botmaker.java.edit.palette.BlockCatalog;
import com.botmaker.java.edit.palette.BlockCategory;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.edit.palette.ExpressionCatalog;
import com.botmaker.java.edit.palette.ExpressionType;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.SourceNode;
import com.botmaker.java.parse.BotParser;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.java.types.MethodSignature;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.java.types.ResolvedType;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import org.eclipse.jdt.core.dom.*;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Edits of one file, each a transaction against a private working copy, found by {@link BlockId} in that copy's tree.
 * A client calls {@link #apply} or one of the edits here, then hands the finished session to
 * {@link CoreEditor#commit}, which is the one place it reaches the project, as one undo step.
 *
 * <p><b>The three checks, and where each lives.</b> A client can only name what the palette offers ({@link #insert}
 * takes a {@link PaletteEntry#id}, never Java); a slot takes a value the host's {@code ValueGrammar} can read as the
 * slot's type and then writes itself ({@link #setSlot}); and every edit is compiled with bindings before it is kept,
 * and dropped if it introduced an error ({@link #apply(EditStage, String, Consumer)}), under
 * {@link EditRequest.Guard#EVERY_EDIT}. The first two refuse what is not in the vocabulary; the third refuses what is
 * in it but does not fit here: deleting a variable something still reads, a call whose arguments do not type.
 *
 * <p><b>Only a new error refuses.</b> A file that already has a problem, a half-finished call the user left, would
 * otherwise refuse every edit including the one that fixes it. Errors are compared as a multiset of problem id plus
 * arguments, not by position, since an insertion moves every offset below it.
 *
 * <p><b>Why a working copy and not the live state.</b> See {@link EditWorkspace}. It also makes a session atomic: a
 * client that gives up half-way leaves nothing behind, and a session that lands is one undo, not twelve.
 *
 * <p>Not thread-confined: nothing here reads the live {@code ProjectState}, unless {@link CoreEditor} handed it the
 * first step on the state's own thread.
 */
public final class EditSession {

    /** What {@link #setSlot} takes before a variable's name: {@code var:value}. */
    public static final String VARIABLE = "var:";

    /** What {@link #setSlot} takes before an operator's id: {@code op:NOT}. */
    public static final String OPERATOR = "op:";

    /** The answer to an edit whose rewrite left the text as it was: nothing to tell a user who dropped a block in place. */
    private static final String UNCHANGED = "That edit changed nothing.";

    private static final int TEXT_LIMIT = 160;

    private final EditWorkspace workspace;
    private final String original;
    private final EditRequest.Guard guard;
    private final List<EditorOutput.Write> writes = new ArrayList<>();
    private final List<BlockType> added = new ArrayList<>();
    private final List<String> editorSaid = new ArrayList<>();
    private Supplier<EditStage> firstStage;
    private String source;

    public EditSession(EditWorkspace workspace, String source) {
        this(workspace, source, EditRequest.Guard.EVERY_EDIT, null);
    }

    EditSession(EditWorkspace workspace, String source, EditRequest.Guard guard, Supplier<EditStage> firstStage) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.original = Objects.requireNonNull(source, "source");
        this.source = source;
        this.guard = guard;
        this.firstStage = firstStage;
    }

    /** The working copy as it stands. */
    public String source() {
        return source;
    }

    /** The text the session started from. */
    public String original() {
        return original;
    }

    /** The file the session edits. */
    public Path file() {
        return workspace.file();
    }

    /** What this session reads the bot through. */
    public EditWorkspace workspace() {
        return workspace;
    }

    /** How many edits this session has kept. */
    public int acceptedEdits() {
        return writes.size();
    }

    /** Takes back every edit past the first {@code keptEdits}: both of a pair or neither. */
    public void rollBackTo(int keptEdits) {
        while (writes.size() > keptEdits) writes.removeLast();
        source = writes.isEmpty() ? original : writes.getLast().newCode();
    }

    /** A fresh step over the working copy. */
    public EditStage stage() {
        if (firstStage != null) {
            EditStage first = firstStage.get();
            firstStage = null;
            return first;
        }
        ProjectState state = workspace.stage(source);
        Path file = workspace.file();
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(workspace.level(), workspace.classpath(), source,
                workspace.sourceRoot(), file == null ? null : file.toAbsolutePath().toString());
        state.setCompilationUnit(cu);
        return new EditStage(workspace.config(), state, cu, workspace.analyzer(state), workspace);
    }

    // ---- reading -----------------------------------------------------------------------------------------

    /**
     * What may be inserted: every statement block the insert menu offers, then every static call of every
     * facade the project's plugins put in a menu, curated exactly as the menu is.
     */
    public List<PaletteEntry> palette() {
        List<PaletteEntry> entries = new ArrayList<>();
        for (BlockType type : BlockCatalog.all()) {
            if (!type.isStatement()) continue;
            entries.add(new PaletteEntry(PaletteEntry.Kind.STATEMENT.idOf(type.id()), PaletteEntry.Kind.STATEMENT,
                    type.displayName(), type.category().getLabel(), List.of(), ""));
        }
        EditStage stage = stage();
        for (FacadeEntry facade : facades()) {
            String owner = facade.simpleName();
            for (MethodSignature sig : offered(stage.analyzer, owner)) {
                entries.add(new PaletteEntry(PaletteEntry.Kind.CALL.idOf(callKey(owner, sig)), PaletteEntry.Kind.CALL,
                        owner + "." + sig.name(), facade.displayLabel(),
                        sig.paramTypes().stream().map(t -> t.simpleName()).toList(),
                        sig.returnType() == null ? "void" : sig.returnType().simpleName()));
            }
        }
        return entries;
    }

    /** The errors the working copy compiles with, one sentence each: what a client reads before it edits. */
    public List<String> errors() {
        return CompileGuard.describe(guard().errorsOf(source), source);
    }

    // ---- editing -----------------------------------------------------------------------------------------

    /** {@code edit} on the working copy. */
    public Outcome apply(Edit edit) {
        return switch (edit) {
            case Edit.Insert insert -> insert(insert.bodyId(), insert.index(), insert.paletteId());
            case Edit.SetSlot set -> setSlot(set.slotId(), set.value().text());
            case Edit.Move move -> moveBlock(move.blockId(), move.bodyId(), move.index());
            case Edit.Delete delete -> delete(delete.blockId());
            case Edit.Rename rename -> rename(rename.symbolId(), rename.name());
            case CanvasEdit ignored -> Outcome.Refused.because("That edit is one only the user makes, by hand.");
        };
    }

    /** Inserts the palette entry {@code paletteId} into body {@code bodyId} at statement index {@code index}. */
    public Outcome insert(String bodyId, int index, String paletteId) {
        EditStage stage = stage();
        Optional<ASTNode> body = stage.body(bodyId);
        if (body.isEmpty()) return Outcome.Refused.because("No body has the id " + bodyId + ". Read the tree again.");
        int at = Math.max(0, Math.min(index, stage.lines(body.get())));

        String blockId = PaletteEntry.Kind.STATEMENT.restOf(paletteId);
        if (blockId != null) {
            Optional<BlockType> type = BlockCatalog.byId(blockId).filter(BlockType::isStatement);
            if (type.isEmpty()) return Outcome.Refused.because("The palette has no statement " + paletteId + ".");
            return apply(stage, "inserted " + type.get().displayName(),
                    editor -> editor.addStatement(bodyId, type.get(), at));
        }
        String callKey = PaletteEntry.Kind.CALL.restOf(paletteId);
        if (callKey != null) {
            Optional<PaletteCall> found = paletteCall(stage.analyzer, callKey);
            if (found.isEmpty()) return Outcome.Refused.because("The palette has no call " + paletteId + ".");
            ExpressionChoice.Method call = found.get().choice();
            if (found.get().facade().entryPoint(call.methodName())) {
                if (!StatementPlacement.inMain(body.get())) {
                    return Outcome.Refused.because(call.scope() + "." + call.methodName()
                            + " starts the bot: it goes in main only.");
                }
                // An entry point is written as the statement menu writes it: Bot.runFlow(Sdk.class), each
                // holder of the bot's values handed over, never a (Class) null no slot can take a class into.
                BlockType entry = new BlockType.LibraryCall("SDK_" + call.scope() + "_" + call.methodName(),
                        call.methodName(), BlockCategory.INPUT, found.get().facade().type(), call.methodName(),
                        List.of());
                return apply(stage, "inserted " + call.scope() + "." + call.methodName(),
                        editor -> editor.addStatement(bodyId, entry, at));
            }
            return apply(stage, "inserted " + call.scope() + "." + call.methodName(),
                    editor -> editor.addMethodCallStatement(bodyId, call, at));
        }
        return Outcome.Refused.because(paletteId + " is not a palette id. Ids start with block: or call:.");
    }

    /** Inserts a block the client's own menu built, which may carry more than a palette id says. */
    Outcome insert(String bodyId, BlockType type, int index) {
        EditStage stage = stage();
        return apply(stage, "inserted " + type.displayName(), editor -> editor.addStatement(bodyId, type, index));
    }

    /**
     * Writes {@code value} into slot {@code slotId}. The value is read by the grammar as the slot's type and
     * written back by it, so what reaches the file is the host's spelling of a value, never the text given.
     */
    public Outcome setSlot(String slotId, String value) {
        EditStage stage = stage();
        Optional<Expression> slot = stage.findNode(slotId, Expression.class);
        if (slot.isEmpty()) return Outcome.Refused.because("No slot has the id " + slotId + ". Read the tree again.");
        ITypeBinding expected = expectedType(slot.get());
        String given = value == null ? "" : value.strip();
        String callKey = PaletteEntry.Kind.CALL.restOf(given);
        if ((callKey != null || given.startsWith(OPERATOR) || given.startsWith(VARIABLE)) && inManagedValue(slot.get())) {
            return Outcome.Refused.because("Slot " + slotId + " is a plugin's managed value: it takes a value, "
                    + "never a call, an operator or a variable.");
        }
        if (callKey != null) return setCall(stage, slotId, slot.get(), expected, callKey);
        if (given.startsWith(VARIABLE)) return setVariable(stage, slotId, slot.get(), given.substring(VARIABLE.length()));
        if (given.startsWith(OPERATOR)) {
            return setOperator(stage, slotId, slot.get(), expected, given.substring(OPERATOR.length()));
        }
        Optional<Type> type = valueType(expected);
        if (type.isEmpty() && expected != null && expected.isEnum()) {
            Optional<JavaValue> constant = enumConstant(expected, given);
            if (constant.isEmpty()) {
                return Outcome.Refused.because("`" + given + "` is none of " + nameOf(expected) + "'s constants: "
                        + enumConstants(expected) + ".");
            }
            return apply(stage, "set " + slotId + " to " + constant.get().source(),
                    editor -> editor.replaceWithValue(slot.get(), constant.get()));
        }
        if (type.isEmpty()) {
            return Outcome.Refused.because("No loaded plugin declares " + nameOf(expected)
                    + ", so a value of it cannot be written here.");
        }
        String text = given;
        List<ManagedConstants.Constant> named = constantsNamed(text);
        if (named.size() > 1) {
            return Outcome.Refused.because("`" + text + "` names " + named.size() + " of the bot's constants: "
                    + named.stream().map(c -> c.owner() + "." + c.field()).toList() + ". Give its owner's full name.");
        }
        if (named.size() == 1) {
            ManagedConstants.Constant constant = named.getFirst();
            // An enum's constant has no initialiser: what it holds is spelled through the grammar instead.
            String held = constant.initializer() != null ? constant.initializer()
                    : workspace.grammar().initializerOfAny(constant.member()).map(JavaValue::source).orElse(null);
            // Read with the bot's own names: an initialiser may name another constant (Way.of(Screens.HOME, …)).
            if (held == null || SourceNode.parse(held)
                    .flatMap(node -> workspace.grammar().read(type.get(), node, workspace.lookup())).isEmpty()) {
                return Outcome.Refused.because("`" + text + "` is not a " + nameOf(expected) + ".");
            }
            JavaValue reference = reference(constant);
            return apply(stage, "set " + slotId + " to " + reference.source(),
                    editor -> editor.replaceWithValue(slot.get(), reference));
        }
        // The bot's constants are read wherever the value holds one: ImageTemplateGroup.of(Pictures.A, Pictures.B).
        Optional<Object> read = SourceNode.parse(text)
                .flatMap(node -> workspace.grammar().read(type.get(), node, workspace.lookup()));
        if (read.isEmpty()) {
            // The type's own spelling, so a near miss (Duration.ofSeconds for Duration.ofMillis) is fixed in one go.
            String like = workspace.grammar().freshSpelling(type.get())
                    .map(fresh -> " It is written like `" + fresh.source() + "`.").orElse("");
            return Outcome.Refused.because("`" + value + "` is not a " + nameOf(expected)
                    + " value. Write a literal, one of the bot's constants (Pictures.ORE), or a constant or "
                    + "factory call the type declares." + like);
        }
        // A value one of the bot's constants holds is written as that constant, as the slot editors write it,
        // and so is each part of it.
        Optional<JavaValue> written = workspace.lookup().spell(read.get())
                .or(() -> workspace.grammar().write(type.get(), read.get(), workspace.lookup()));
        if (written.isEmpty()) return Outcome.Refused.because("That value cannot be written as " + nameOf(expected) + ".");
        return apply(stage, "set " + slotId + " to " + written.get().source(),
                editor -> editor.replaceWithValue(slot.get(), written.get()));
    }

    /**
     * Writes the palette's call {@code key} into a slot its result fits, as the slot's menu does: its arguments
     * are the call's defaults, each a slot of its own to set next.
     */
    private Outcome setCall(EditStage stage, String slotId, Expression slot, ITypeBinding expected, String key) {
        Optional<PaletteCall> call = paletteCall(stage.analyzer, key);
        if (call.isEmpty()) return Outcome.Refused.because("The palette has no call " + PaletteEntry.Kind.CALL.idOf(key) + ".");
        ResolvedType returns = call.get().signature().returnType();
        if (expected == null) return Outcome.Refused.because("Slot " + slotId + " has no type Studio can read.");
        if (returns == null || returns.isVoid() || !returns.isAssignmentCompatible(ResolvedType.of(expected))) {
            return Outcome.Refused.because(key + " gives " + (returns == null ? "nothing" : returns.simpleName())
                    + ", not a " + nameOf(expected) + ".");
        }
        ExpressionChoice.Method choice = call.get().choice();
        return apply(stage, "set " + slotId + " to " + choice.scope() + "." + choice.methodName()
                + "(…); read the tree for its arguments' slots", editor -> editor.replaceWithMethodCall(slot, choice));
    }

    /**
     * Writes an operator ({@code op:NOT}, {@code op:AND}, {@code op:LESS}…) into a slot its result fits, as the
     * slot's menu does: its operands are placeholders, each a slot of its own to set next.
     */
    private Outcome setOperator(EditStage stage, String slotId, Expression slot, ITypeBinding expected, String id) {
        Optional<ExpressionType> operator = ExpressionCatalog.byId(id).filter(EditSession::isOperator);
        if (operator.isEmpty()) {
            return Outcome.Refused.because("`" + OPERATOR + id + "` is not an operator. Operators: "
                    + String.join(", ", operatorIds()) + ".");
        }
        if (expected == null) return Outcome.Refused.because("Slot " + slotId + " has no type Studio can read.");
        if (!ExpressionCatalog.isCompatibleWith(operator.get(), ResolvedType.of(expected), stage.state)) {
            return Outcome.Refused.because(operator.get().displayName() + " does not give a " + nameOf(expected) + ".");
        }
        return apply(stage, "set " + slotId + " to " + operator.get().displayName()
                + "; read the tree for its operands' slots", editor -> editor.replaceExpression(slot, operator.get()));
    }

    /**
     * Whether {@code node} is inside a member a plugin's marker annotates: a managed value, whose expression only
     * the grammar writes and the host reads back (umbrella CLAUDE.md, a plugin's values).
     */
    private static boolean inManagedValue(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (!(at instanceof BodyDeclaration declaration)) continue;
            for (Object modifier : declaration.modifiers()) {
                if (modifier instanceof Annotation annotation && ManagedIds.isMarker(annotation.resolveTypeBinding())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Writes the variable {@code name} into a slot: a local, a parameter, a lambda's parameter or a field in
     * scope there. Only a name is written; the compile check refuses one out of scope or of another type.
     */
    private Outcome setVariable(EditStage stage, String slotId, Expression slot, String name) {
        String wanted = name.strip();
        if (!SourceVersion.isIdentifier(wanted) || SourceVersion.isKeyword(wanted)) {
            return Outcome.Refused.because("`" + wanted + "` is not a variable's name.");
        }
        return apply(stage, "set " + slotId + " to " + wanted, editor -> editor.replaceWithVariable(slot, wanted));
    }

    /** Adds an argument to the varargs call {@code callId}: a call statement's id, or a slot holding a call. */
    public Outcome addArgument(String callId) {
        EditStage stage = stage();
        Optional<MethodInvocation> call = stage.findNode(callId, MethodInvocation.class);
        if (call.isEmpty()) return Outcome.Refused.because("No call has the id " + callId + ". Read the tree again.");
        IMethodBinding binding = call.get().resolveMethodBinding();
        if (binding == null || !binding.isVarargs()) {
            return Outcome.Refused.because(call.get().getName().getIdentifier() + " takes no more arguments.");
        }
        ITypeBinding[] params = binding.getParameterTypes();
        ResolvedType element = ResolvedType.of(params[params.length - 1].getElementType());
        return apply(stage, "added an argument to " + call.get().getName().getIdentifier()
                + "; read the tree for its slot", editor -> editor.addVarargsArgument(call.get(), element));
    }

    /** Moves the statement {@code blockId} into body {@code bodyId}, before the statement now at {@code index}. */
    public Outcome moveBlock(String blockId, String bodyId, int index) {
        EditStage stage = stage();
        Optional<Statement> statement = stage.statement(blockId);
        if (statement.isEmpty()) return Outcome.Refused.because("No statement has the id " + blockId + ".");
        Optional<ASTNode> target = stage.body(bodyId);
        if (target.isEmpty()) return Outcome.Refused.because("No body has the id " + bodyId + ". Read the tree again.");
        // Into itself would remove it and insert into what was removed: the statement would vanish, and a
        // deletion compiles, so the compile check would not catch it.
        for (ASTNode at = target.get(); at != null; at = at.getParent()) {
            if (at == statement.get()) {
                return Outcome.Refused.because("A statement cannot move into a body inside itself.");
            }
        }
        Optional<ASTNode> from = stage.bodyOf(statement.get());
        if (from.isEmpty()) return Outcome.Refused.because("Statement " + blockId + " is not in a body it can leave.");
        int at = Math.max(0, Math.min(index, stage.lines(target.get())));
        return apply(stage, "moved " + oneLine(statement.get(), source),
                editor -> editor.moveStatement(BlockId.of(statement.get()), BlockId.of(from.get()), bodyId, at));
    }

    /** Deletes the statement {@code blockId}. */
    public Outcome delete(String blockId) {
        EditStage stage = stage();
        Optional<Statement> statement = stage.statement(blockId);
        if (statement.isEmpty()) return Outcome.Refused.because("No statement has the id " + blockId + ".");
        return apply(stage, "deleted " + oneLine(statement.get(), source),
                editor -> editor.deleteStatement(statement.get()));
    }

    /**
     * Renames the local variable {@code symbolId} declares or names, with its uses. A field, a method or a type crosses
     * files, which is a refactor over the whole bot and not this one file's edit: refused here for now.
     */
    public Outcome rename(String symbolId, String name) {
        EditStage stage = stage();
        Optional<ASTNode> node = stage.findNode(symbolId, ASTNode.class);
        if (node.isEmpty()) return Outcome.Refused.because("No symbol has the id " + symbolId + ". Read the tree again.");
        Optional<SimpleName> declared = declaredLocal(node.get(), stage.cu);
        if (declared.isEmpty()) {
            return Outcome.Refused.because("Only a local variable can be renamed by its id; rename names anything else.");
        }
        String wanted = name.strip();
        if (!SourceVersion.isIdentifier(wanted) || SourceVersion.isKeyword(wanted)) {
            return Outcome.Refused.because("`" + wanted + "` is not a variable's name.");
        }
        return apply(stage, "renamed " + declared.get().getIdentifier() + " to " + wanted,
                editor -> editor.renameLocal(declared.get(), wanted));
    }

    /** The name a local's declaration gives it, from the declaring statement, the fragment or any use of the name. */
    private static Optional<SimpleName> declaredLocal(ASTNode node, CompilationUnit cu) {
        return switch (node) {
            case VariableDeclarationStatement statement when statement.fragments().size() == 1 ->
                    Optional.of(((VariableDeclarationFragment) statement.fragments().getFirst()).getName());
            case VariableDeclarationFragment fragment when !(fragment.getParent() instanceof FieldDeclaration) ->
                    Optional.of(fragment.getName());
            case SimpleName use when use.resolveBinding() instanceof IVariableBinding variable
                    && !variable.isField() && !variable.isParameter()
                    && cu.findDeclaringNode(variable) instanceof VariableDeclarationFragment fragment ->
                    Optional.of(fragment.getName());
            default -> Optional.empty();
        };
    }

    // ---- the transaction ---------------------------------------------------------------------------------

    /**
     * Runs one {@link CodeEditor} call over {@code stage} and keeps its output only when {@link #guard} lets it. The
     * editor's own guards still apply (the lock, and its refusal of source that does not parse) and their status lines
     * become the refusal's reasons.
     */
    public Outcome apply(EditStage stage, String summary, Consumer<CodeEditor> edit) {
        edit.accept(stage.editor);
        if (stage.written == null) {
            editorSaid.addAll(stage.status);
            return new Outcome.Refused(stage.status.isEmpty() ? List.of(UNCHANGED) : stage.status);
        }
        String published = stage.written.newCode();
        if (guard == EditRequest.Guard.EVERY_EDIT) {
            List<CompileGuard.Problem> introduced = guard().introduced(source, published);
            if (!introduced.isEmpty()) {
                List<String> reasons = new ArrayList<>();
                reasons.add("That edit would not compile, so nothing was changed:");
                reasons.addAll(CompileGuard.describe(introduced, published));
                return new Outcome.Refused(reasons);
            }
        }
        source = published;
        writes.add(stage.written);
        added.addAll(stage.added);
        return new Outcome.Accepted(summary);
    }

    private CompileGuard guard() {
        return new CompileGuard(new BotParser(workspace.level(), workspace.classpath(), workspace.sourceRoot()),
                workspace.file());
    }

    /**
     * The session as one write of the live file, or empty when nothing was kept, or when {@code liveSource} is no longer
     * the text the session started from, because someone else edited meanwhile and publishing now would silently undo
     * them. With {@code label} it is the client's one step; without, a single edit is itself and several are named by the
     * last.
     */
    public Optional<EditorOutput.Write> finished(String liveSource, String label) {
        if (writes.isEmpty() || source.equals(original) || !original.equals(liveSource)) return Optional.empty();
        if (label != null) return Optional.of(new EditorOutput.Write(source, original, false, label, List.of()));
        if (writes.size() == 1) return Optional.of(writes.getFirst());
        boolean unedited = writes.stream().anyMatch(EditorOutput.Write::markNewIdentifiersAsUnedited);
        return Optional.of(new EditorOutput.Write(source, original, unedited, writes.getLast().label(),
                writes.stream().flatMap(write -> write.alsoChanged().stream()).toList()));
    }

    /** The status lines {@code CodeEditor} wrote for the user when it refused, not the reasons worded for a model. */
    List<String> editorSaid() {
        return List.copyOf(editorSaid);
    }

    /** The palette blocks the kept edits added, to announce once they land. */
    List<BlockType> added() {
        return List.copyOf(added);
    }

    // ---- the palette's calls -----------------------------------------------------------------------------

    private static List<FacadeEntry> facades() {
        return PluginHost.menuFacades();
    }

    /** The static overloads of {@code owner} the menu offers, in the menu's order. */
    private static List<MethodSignature> offered(ProjectAnalyzer analyzer, String owner) {
        List<MethodSignature> out = new ArrayList<>();
        Map<String, List<MethodSignature>> byName = new TreeMap<>();
        for (MethodSignature sig : analyzer.getMethods(owner, true)) {
            byName.computeIfAbsent(sig.name(), k -> new ArrayList<>()).add(sig);
        }
        PaletteCuration curation = PaletteCuration.current();
        List<String> names = curation.retainOfferedNames(owner, new ArrayList<>(byName.keySet()), null);
        for (String name : names) {
            List<MethodSignature> sigs = curation.retainOffered(owner, name, byName.get(name), null);
            out.addAll(sigs);
        }
        return out;
    }

    private static String callKey(String owner, MethodSignature sig) {
        return owner + "." + sig.name() + "(" + sig.signatureKey() + ")";
    }

    /** A call the palette offers: its facade, its owner's simple name and its signature. */
    private record PaletteCall(FacadeEntry facade, String owner, MethodSignature signature) {
        ExpressionChoice.Method choice() {
            return new ExpressionChoice.Method(owner, signature.name(), signature.paramTypes(), true);
        }
    }

    private static Optional<PaletteCall> paletteCall(ProjectAnalyzer analyzer, String key) {
        for (FacadeEntry facade : facades()) {
            String owner = facade.simpleName();
            for (MethodSignature sig : offered(analyzer, owner)) {
                if (callKey(owner, sig).equals(key)) return Optional.of(new PaletteCall(facade, owner, sig));
            }
        }
        return Optional.empty();
    }

    // ---- values ------------------------------------------------------------------------------------------

    /** The operators a slot takes, as {@link #setSlot} spells them, in the menu's order. */
    public static List<String> operatorIds() {
        return ExpressionCatalog.all().stream().filter(EditSession::isOperator).map(t -> OPERATOR + t.id()).toList();
    }

    /** An operator, or a lambda: the code a call such as {@code ImageFinder.whileFindAny} runs, its body a body. */
    private static boolean isOperator(ExpressionType type) {
        return type instanceof ExpressionType.InfixOp || type instanceof ExpressionType.PrefixOp
                || type == ExpressionCatalog.LAMBDA || type == ExpressionCatalog.NEGATE || type == ExpressionCatalog.CHOOSE;
    }

    /** The bot's managed constants {@code text} names as {@code Owner.FIELD}, by simple or full owner. */
    private List<ManagedConstants.Constant> constantsNamed(String text) {
        int dot = text.lastIndexOf('.');
        if (dot <= 0 || dot == text.length() - 1) return List.of();
        String owner = text.substring(0, dot);
        String field = text.substring(dot + 1);
        return workspace.constants().stream()
                .filter(c -> field.equals(c.field()) && (c.owner().equals(owner) || c.simpleOwner().equals(owner)))
                .toList();
    }

    /**
     * One of the enum {@code type}'s constants, named bare ({@code NEXT}) or after any part of the type's name
     * ({@code Research.NEXT}, {@code Activities.Research.NEXT}), written as a file names it with the top-level
     * type imported: an activity's outcome, an enum no plugin declares (2026-10-13).
     */
    public static Optional<JavaValue> enumConstant(ITypeBinding type, String given) {
        int dot = given.lastIndexOf('.');
        String name = given.substring(dot + 1);
        String qualifier = dot < 0 ? "" : given.substring(0, dot);
        List<String> chain = new ArrayList<>();
        ITypeBinding top = type.getErasure();
        for (ITypeBinding at = top; at != null; at = at.getDeclaringClass()) {
            chain.addFirst(at.getName());
            top = at;
        }
        String written = String.join(".", chain);
        if (!qualifier.isEmpty() && !(written.equals(qualifier) || written.endsWith("." + qualifier)
                || type.getErasure().getQualifiedName().equals(qualifier))) {
            return Optional.empty();
        }
        if (!enumConstants(type).contains(name)) return Optional.empty();
        AST ast = JavaLevel.LATEST.newAst();
        QualifiedName constant = ast.newQualifiedName(ast.newName(written), ast.newSimpleName(name));
        // A local enum has no name to import; it is in scope where it is used.
        String imported = top.getQualifiedName();
        return Optional.of(JavaValue.built(constant, imported.isEmpty() || top.isLocal() ? List.of() : List.of(imported)));
    }

    private static List<String> enumConstants(ITypeBinding type) {
        return Arrays.stream(type.getDeclaredFields()).filter(IVariableBinding::isEnumConstant)
                .map(IVariableBinding::getName).toList();
    }

    /** {@code Owner.FIELD}, {@code Activities.Collect.WON} for a nested enum's, importing its top-level class. */
    private static JavaValue reference(ManagedConstants.Constant constant) {
        AST ast = JavaLevel.LATEST.newAst();
        QualifiedName name = ast.newQualifiedName(ast.newName(constant.simpleOwner()),
                ast.newSimpleName(constant.field()));
        return JavaValue.built(name, List.of(constant.topLevel()));
    }

    // ---- types -------------------------------------------------------------------------------------------

    /**
     * The type {@code expression}'s position expects: the declared parameter of the call it is an argument
     * of (the varargs element past the last one), the declared type of the variable it initialises, and the
     * expression's own type anywhere else.
     */
    public static ITypeBinding expectedType(Expression expression) {
        ASTNode parent = expression.getParent();
        IMethodBinding call = switch (parent) {
            case MethodInvocation m when m.arguments().contains(expression) -> m.resolveMethodBinding();
            case ClassInstanceCreation c when c.arguments().contains(expression) -> c.resolveConstructorBinding();
            default -> null;
        };
        if (call != null) {
            List<?> arguments = parent instanceof MethodInvocation m ? m.arguments() : ((ClassInstanceCreation) parent).arguments();
            int index = arguments.indexOf(expression);
            ITypeBinding[] params = call.getParameterTypes();
            if (index < params.length) {
                ITypeBinding param = params[index];
                return call.isVarargs() && index == params.length - 1 && param.isArray()
                        && !(expression.resolveTypeBinding() != null && expression.resolveTypeBinding().isArray())
                        ? param.getElementType() : param;
            }
            if (call.isVarargs() && params.length > 0) return params[params.length - 1].getElementType();
        }
        if (parent instanceof VariableDeclarationFragment fragment
                && fragment.getParent() instanceof VariableDeclarationStatement declaration) {
            ITypeBinding declared = declaration.getType().resolveBinding();
            if (declared != null) return declared;
        }
        // A return gives what its method declares: a seeded `return null;` is typed null otherwise (2026-10-13).
        if (parent instanceof ReturnStatement) {
            ASTNode at = parent;
            while (at != null && !(at instanceof MethodDeclaration) && !(at instanceof LambdaExpression)) {
                at = at.getParent();
            }
            ITypeBinding declared = switch (at) {
                case MethodDeclaration method when method.getReturnType2() != null ->
                        method.getReturnType2().resolveBinding();
                case LambdaExpression lambda when lambda.resolveMethodBinding() != null ->
                        lambda.resolveMethodBinding().getReturnType();
                case null, default -> null;
            };
            if (declared != null) return declared;
        }
        return expression.resolveTypeBinding();
    }

    /** The grammar's type for {@code binding}, by its exact erased name, or empty when no plugin declares it. */
    public Optional<Type> valueType(ITypeBinding binding) {
        if (binding == null || binding.isArray() || binding.isNullType()) return Optional.empty();
        String name = binding.isPrimitive() ? binding.getName() : binding.getErasure().getQualifiedName();
        return workspace.grammar().named(name).<Type>map(cls -> cls);
    }

    public static String nameOf(ITypeBinding binding) {
        if (binding == null) return "an unresolved type";
        return binding.isPrimitive() ? binding.getName() : binding.getErasure().getQualifiedName();
    }

    /** {@code node}'s text on one line, cut short, as a sentence quotes it. */
    public static String oneLine(ASTNode node, String code) {
        int start = node.getStartPosition();
        int end = start + node.getLength();
        String text = start >= 0 && end <= code.length() ? code.substring(start, end) : node.toString();
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT - 1) + "…";
    }
}
