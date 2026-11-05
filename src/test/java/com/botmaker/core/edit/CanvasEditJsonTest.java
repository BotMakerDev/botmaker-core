package com.botmaker.core.edit;

import com.botmaker.core.CoreJson;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.java.edit.ExpressionChoice;
import com.botmaker.java.edit.UseFix;
import com.botmaker.java.edit.palette.BlockCatalog;
import com.botmaker.java.edit.palette.BlockCategory;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.java.edit.palette.BotType;
import com.botmaker.java.edit.palette.EnumDraft;
import com.botmaker.java.edit.palette.ExpressionCatalog;
import com.botmaker.java.edit.palette.FunctionDraft;
import com.botmaker.java.edit.palette.Initializer;
import com.botmaker.java.edit.palette.SignatureType;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueTypes;
import com.botmaker.java.types.ResolvedType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every gesture crosses as JSON and comes back the same. Compared by what it writes, since a {@link JavaValue} holds a
 * tree, which has no equality, and a {@link ResolvedType} read back is the plain type of its name.
 */
class CanvasEditJsonTest {

    private static final String ID = "types[0]/bodyDeclarations[1]";
    private static final String OTHER = ID + "/body";
    private static final ResolvedType TEXT = ResolvedType.named("java.lang.String");
    private static final JavaValue VALUE = JavaValue.parse("1 + 2").orElseThrow();
    private static final CanvasEdit.Pick PICK = CanvasEdit.Pick.of(new ExpressionChoice.Variable("a"));
    private static final BlockType BLOCK = new BlockType.AssignTo("assign", "Assign", BlockCategory.values()[0], "a");
    private static final EnumDraft ENUM = new EnumDraft("Mode", List.of("ON", "OFF"));
    private static final FunctionDraft FUNCTION = new FunctionDraft("run", new SignatureType.Kept("int"),
            List.of(new FunctionDraft.Parameter("a", new SignatureType.Typed(String.class, "\"\""), 0)));

    private static final List<CanvasEdit> EVERY_GESTURE = List.of(
            new CanvasEdit.SetLiteral(ID, "x"),
            new CanvasEdit.SetCharacter(ID, "x"),
            new CanvasEdit.SetNumberToken(ID, "1"),
            new CanvasEdit.SetTextBlock(ID, "x"),
            new CanvasEdit.SetExpressionSource(ID, "a + b"),
            new CanvasEdit.SetSimpleName(ID, "a"),
            new CanvasEdit.SetValue(ID, VALUE),
            new CanvasEdit.SetExpressionType(ID, "int"),
            new CanvasEdit.SetAssignmentOperator(ID, "+="),
            new CanvasEdit.SetBinaryOperator(ID, "+"),
            new CanvasEdit.Replace(ID, PICK),
            new CanvasEdit.FillEmptySlot(ID, PICK, TEXT),
            new CanvasEdit.RenameLocal(ID, "a"),
            new CanvasEdit.SetCall(ID, "Scope", "run", List.of(TEXT)),
            new CanvasEdit.SetInstantiation(ID, "Thing", List.of(TEXT)),
            new CanvasEdit.AddStringArgument(ID, "x"),
            new CanvasEdit.AddVarargsArgument(ID, TEXT),
            new CanvasEdit.DeleteArgument(ID, 1),
            new CanvasEdit.SetTrailingArguments(ID, 1, List.of(VALUE)),
            new CanvasEdit.MoveListElement(ID, 0, 1),
            new CanvasEdit.AddSeededListElement(ID, 0, TEXT),
            new CanvasEdit.InsertIntoList(ID, 0, PICK, TEXT),
            new CanvasEdit.DeleteListElement(ID, 0),
            new CanvasEdit.AddBranchLink(ID, 0),
            new CanvasEdit.RemoveBranchLink(ID, 0),
            new CanvasEdit.AddElse(ID),
            new CanvasEdit.DeleteElse(ID),
            new CanvasEdit.ConvertElseToElseIf(ID),
            new CanvasEdit.AddCase(ID),
            new CanvasEdit.AddCases(ID, List.of("A", "B")),
            new CanvasEdit.MoveCase(ID, true),
            new CanvasEdit.AddSwitchExpressionCase(ID),
            new CanvasEdit.RemoveSwitchExpressionCase(ID, 0),
            new CanvasEdit.NormalizeSwitches(),
            new CanvasEdit.AddCatch(ID),
            new CanvasEdit.DeleteCatch(ID, 0),
            new CanvasEdit.SetCatchType(ID, "Exception"),
            new CanvasEdit.AddFinally(ID),
            new CanvasEdit.DeleteFinally(ID),
            new CanvasEdit.SetReturn(ID, PICK),
            new CanvasEdit.DeclareLocal(ID, 0, "a", String.class),
            new CanvasEdit.SetVariableType(ID, new ValueTypes.Parameterized(List.class,
                    List.of(new ValueTypes.BotClass("t.Mine", List.of()), new ValueTypes.Unknown("Gone")))),
            new CanvasEdit.SetVariableInitializer(ID, PICK),
            new CanvasEdit.DeleteVariable(ID, UseFix.DEFAULT),
            new CanvasEdit.SetFieldType(ID, "int"),
            new CanvasEdit.SetFieldDefault(ID, TEXT),
            new CanvasEdit.InsertBlock(ID, 0, BLOCK),
            new CanvasEdit.AddEnumStatement(ID, 0, ENUM),
            new CanvasEdit.InsertStatement(ID, 0, "run();", List.of("t.Thing")),
            new CanvasEdit.Paste(ID, 0, "run();"),
            new CanvasEdit.SetStatementSource(ID, "run();"),
            new CanvasEdit.UpdateComment(ID, "note"),
            new CanvasEdit.DeleteComment(ID),
            new CanvasEdit.FillSlotFromBlock(ID, BLOCK),
            new CanvasEdit.FillEmptySlotFromBlock(ID, BLOCK),
            new CanvasEdit.MoveBetweenSlots(ID, OTHER),
            new CanvasEdit.FillEmptySlotFromExpression(ID, OTHER),
            new CanvasEdit.MoveLineIntoSlot(ID, OTHER),
            new CanvasEdit.FillEmptySlotFromLine(ID, OTHER),
            new CanvasEdit.MoveExpressionToLine(ID, OTHER, 0),
            new CanvasEdit.MoveMember(ID, OTHER, 0),
            new CanvasEdit.AddMethod(ID, "run", "void", 0),
            new CanvasEdit.AddFunction(ID, FUNCTION, 0),
            new CanvasEdit.DeleteMethod(ID),
            new CanvasEdit.RenameParameter(ID, 0, "a"),
            new CanvasEdit.SetSignature(ID, FUNCTION, FUNCTION),
            new CanvasEdit.RemoveAnnotation(ID, OTHER),
            new CanvasEdit.AddEnum(ID, "Mode", 0),
            new CanvasEdit.AddEnumDraft(ID, ENUM, 0),
            new CanvasEdit.DeleteEnum(ID),
            new CanvasEdit.AddEnumConstant(ID, "ON"),
            new CanvasEdit.DeleteEnumConstant(ID, 0),
            new CanvasEdit.ApplyRefactor(ID, "Renamed", new LinkedHashMap<>(Map.of("/a/B.java", "class B {}")),
                    new LinkedHashMap<>(Map.of("/a/C.java", "/a/D.java"))),
            new CanvasEdit.RestoreMethod("void run() {}", "run()"),
            new CanvasEdit.RestoreFile("class B {}"));

    private static void roundTrips(Object message, Class<?> as) {
        String json = CoreJson.write(message);
        assertEquals(json, CoreJson.write(CoreJson.read(json, as)));
    }

    @Test
    void everyGestureRoundTrips() {
        EVERY_GESTURE.forEach(gesture -> roundTrips(gesture, Edit.class));
    }

    /** A new kind of gesture is a protocol change: it joins the list above, or this fails. */
    @Test
    void theListAboveHoldsEveryKindOfGesture() {
        Set<Class<?>> kinds = Arrays.stream(CanvasEdit.class.getPermittedSubclasses()).collect(Collectors.toSet());
        assertEquals(kinds, EVERY_GESTURE.stream().map(Object::getClass).collect(Collectors.toSet()));
    }

    @Test
    void everyBlockAndExpressionTheMenusOfferRoundTrips() {
        BlockCatalog.all().forEach(block -> roundTrips(new CanvasEdit.InsertBlock(ID, 0, block), Edit.class));
        ExpressionCatalog.all().forEach(type ->
                roundTrips(new CanvasEdit.Replace(ID, CanvasEdit.Pick.of(type)), Edit.class));
    }

    @Test
    void everyKindOfInitializerAndPickRoundTrips() {
        List<Initializer> every = List.of(new Initializer.IntLit("1"), new Initializer.DoubleLit("1.0"),
                new Initializer.BoolLit(true), new Initializer.CharLit('c'), new Initializer.StrLit("s"),
                new Initializer.NullLit(), new Initializer.NewInstance("T", List.of(new Initializer.NullLit())),
                new Initializer.EnumConst("Mode", "ON"), new Initializer.StaticCall("T", "m", List.of()),
                new Initializer.Raw("1"));
        BlockType call = new BlockType.LibraryCall("call", "Call", BlockCategory.values()[0], String.class, "valueOf", every);
        roundTrips(new CanvasEdit.InsertBlock(ID, 0, call), Edit.class);

        for (ExpressionChoice choice : List.of(new ExpressionChoice.ArrayItem("a"),
                new ExpressionChoice.Method("S", "m", List.of(TEXT), true), new ExpressionChoice.Field("S", "f"),
                new ExpressionChoice.Constructor("T", List.of(TEXT)), new ExpressionChoice.EnumConstant("Mode", "ON"),
                new ExpressionChoice.Variable("a"), new ExpressionChoice.NewVariable(TEXT, "a"),
                new ExpressionChoice.RawExpression("1"))) {
            roundTrips(new CanvasEdit.Replace(ID, CanvasEdit.Pick.of(choice)), Edit.class);
        }
    }

    @Test
    void aSignatureTypeARuleOfferedRoundTrips() {
        BotType.Choice choice = BotType.Choice.fromSourceName("int", PluginHost.grammar()).orElseThrow();
        FunctionDraft draft = new FunctionDraft("run", new SignatureType.Described(choice), List.of());
        roundTrips(new CanvasEdit.AddFunction(ID, draft, 0), Edit.class);
    }

    @Test
    void aValueCrossesAsItsSourceAndATypeReadBackIsTheSameName() {
        CanvasEdit.SetValue value = assertInstanceOf(CanvasEdit.SetValue.class,
                CoreJson.read(CoreJson.write(new CanvasEdit.SetValue(ID, VALUE)), Edit.class));
        assertEquals("1 + 2", value.value().source());

        CanvasEdit.AddVarargsArgument typed = assertInstanceOf(CanvasEdit.AddVarargsArgument.class,
                CoreJson.read(CoreJson.write(new CanvasEdit.AddVarargsArgument(ID, TEXT)), Edit.class));
        assertEquals(TEXT, typed.elementType());

        Type read = assertInstanceOf(CanvasEdit.DeclareLocal.class, CoreJson.read(
                CoreJson.write(new CanvasEdit.DeclareLocal(ID, 0, "a", String.class)), Edit.class)).type();
        assertEquals(String.class, read);
    }

    @Test
    void aGestureIsNamedOnTheWireAndAWriteMakesNoFieldOfAMethod() {
        String json = CoreJson.write(new CanvasEdit.Replace(ID, PICK));
        assertTrue(json.contains("\"edit\":\"replace\""), json);
        assertFalse(json.contains("\"value\""), json);
    }

    @Test
    void aValueThatIsNotOneExpressionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CoreJson.read(
                "{\"edit\":\"setValue\",\"id\":\"i\",\"value\":{\"source\":\"int a = 1;\",\"imports\":[]}}", Edit.class));
    }
}
