package com.botmaker.core.edit;

import com.botmaker.core.CoreJson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every edit, request and result crosses the protocol as JSON and comes back equal. */
class EditJsonTest {

    private static final String BODY = "types[0]/bodyDeclarations[1]/body";

    private static final List<Edit> EVERY_EDIT = List.of(
            new Edit.Insert(BODY, 0, "sdk.click"),
            new Edit.SetSlot(BODY + "/statements[0]/expression/arguments[0]", new SlotValue("Pictures.ORE")),
            new Edit.Move(BODY + "/statements[2]", BODY, 1),
            new Edit.Delete(BODY + "/statements[3]"),
            new Edit.Rename("types[0]/bodyDeclarations[1]/name", "mine"));

    @Test
    void everyEditRoundTrips() {
        for (Edit edit : EVERY_EDIT) {
            assertEquals(edit, CoreJson.read(CoreJson.write(edit), Edit.class), CoreJson.write(edit));
        }
    }

    /** A new kind of edit is a protocol change: it joins the list above, or this fails. */
    @Test
    void theListAboveHoldsEveryKindOfEdit() {
        Set<Class<?>> kinds = Arrays.stream(Edit.class.getPermittedSubclasses()).collect(Collectors.toSet());
        assertEquals(kinds, EVERY_EDIT.stream().map(Object::getClass).collect(Collectors.toSet()));
    }

    @Test
    void anEditIsNamedOnTheWireAndASlotValueIsABareString() {
        assertEquals("{\"edit\":\"setSlot\",\"slotId\":\"s\",\"value\":\"12\"}",
                CoreJson.write(new Edit.SetSlot("s", new SlotValue("12"))));
        assertEquals("\"UNDO\"", CoreJson.write(HistoryStep.UNDO));
        assertEquals(HistoryStep.REDO, CoreJson.read("\"REDO\"", HistoryStep.class));
    }

    @Test
    void aRequestAndBothResultsRoundTrip() {
        EditRequest request = new EditRequest("com/mybot/Main.java", EVERY_EDIT);
        assertEquals(request, CoreJson.read(CoreJson.write(request), EditRequest.class));
        EditRequest byHand = new EditRequest("com/mybot/Main.java", EVERY_EDIT, EditRequest.Guard.EDITOR_RULES);
        assertEquals(byHand, CoreJson.read(CoreJson.write(byHand), EditRequest.class));

        ChangedMethod run = new ChangedMethod("types[0]/bodyDeclarations[1]", "run", "public void run() {\n}",
                List.of(new ChangedMethod.Node("types[0]/bodyDeclarations[1]/body", 18, 3)));
        for (EditResult result : List.of(new EditResult.Accepted(List.of(run)),
                new EditResult.Refused("Pictures.ORE is not a number."))) {
            assertEquals(result, CoreJson.read(CoreJson.write(result), EditResult.class));
        }
    }

    /** What a constructor refuses, the wire refuses too: no malformed edit reaches the core. */
    @Test
    void aMalformedEditIsRefusedWithTheReason() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
                CoreJson.read("{\"edit\":\"insert\",\"bodyId\":\"b\",\"index\":-1,\"paletteId\":\"p\"}", Edit.class));
        assertTrue(refused.getMessage().contains("below 0"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CoreJson.read("{\"edit\":\"paste\"}", Edit.class));
        assertThrows(IllegalArgumentException.class, () ->
                CoreJson.read("{\"edit\":\"delete\",\"blockId\":\"b\",\"extra\":1}", Edit.class));
    }

    /** A missing index is refused, not read as 0, and so is text after the message. */
    @Test
    void aMissingComponentOrTrailingTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () ->
                CoreJson.read("{\"edit\":\"insert\",\"bodyId\":\"b\",\"paletteId\":\"p\"}", Edit.class));
        assertThrows(IllegalArgumentException.class, () ->
                CoreJson.read("{\"edit\":\"insert\",\"bodyId\":\"b\",\"index\":null,\"paletteId\":\"p\"}", Edit.class));
        assertThrows(IllegalArgumentException.class, () ->
                CoreJson.read("{\"edit\":\"delete\",\"blockId\":\"b\"} {}", Edit.class));
    }

    @Test
    void aNodePastItsMethodsSourceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ChangedMethod("m", "run", "run(){}",
                List.of(new ChangedMethod.Node("m/body", 5, 3))));
    }
}
