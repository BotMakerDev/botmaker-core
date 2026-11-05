package com.botmaker.core.event;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.ChangedMethod;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every event crosses the protocol as JSON and comes back equal. */
class CoreEventJsonTest {

    private static final String FILE = "com/mybot/Main.java";

    private static final List<CoreEvent> EVERY_EVENT = List.of(
            new CoreEvent.MethodsChanged(FILE, List.of(new ChangedMethod("types[0]/bodyDeclarations[0]", "main",
                    "public static void main(String[] args) {\n}",
                    List.of(new ChangedMethod.Node("types[0]/bodyDeclarations[0]/body", 39, 3)))),
                    List.of("types[0]/bodyDeclarations[3]")),
            new CoreEvent.Diagnostics(FILE, List.of(
                    new CoreEvent.Diagnostics.Problem("types[0]/bodyDeclarations[0]/body/statements[0]",
                            CoreEvent.Diagnostics.Severity.ERROR, 4, "ore cannot be resolved"),
                    new CoreEvent.Diagnostics.Problem("", CoreEvent.Diagnostics.Severity.WARNING, 1,
                            "The import java.util.List is never used"))),
            new CoreEvent.RunState(CoreEvent.RunState.Phase.ENDED, "stopped by the user"),
            new CoreEvent.RunOutput(CoreEvent.RunOutput.Stream.ERR, "Ore not found\n"),
            new CoreEvent.UiRequest("7", CoreEvent.UiRequest.Kind.WINDOW, "Screens"),
            new CoreEvent.Refusal(FILE, "Pictures.ORE is not a number."));

    @Test
    void everyEventRoundTrips() {
        for (CoreEvent event : EVERY_EVENT) {
            assertEquals(event, CoreJson.read(CoreJson.write(event), CoreEvent.class), CoreJson.write(event));
        }
    }

    /** A new kind of event is a protocol change: it joins the list above, or this fails. */
    @Test
    void theListAboveHoldsEveryKindOfEvent() {
        Set<Class<?>> kinds = Arrays.stream(CoreEvent.class.getPermittedSubclasses()).collect(Collectors.toSet());
        assertEquals(kinds, EVERY_EVENT.stream().map(Object::getClass).collect(Collectors.toSet()));
    }

    @Test
    void anEventIsNamedOnTheWire() {
        assertEquals("{\"event\":\"runState\",\"phase\":\"RUNNING\",\"detail\":\"\"}",
                CoreJson.write(new CoreEvent.RunState(CoreEvent.RunState.Phase.RUNNING, null)));
    }
}
