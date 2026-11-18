// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.plugin.api.run.RunEntry;
import com.botmaker.plugin.host.PluginCoordinate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code operations}: each plugin's tools and runs with their schemas, in the JSON the cli's {@code botmaker tool} reads. */
class OperationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    enum Mode { QUICK, FULL }

    record Look(@Describe("what to look for") String what, @Describe(value = "how", optional = true) Mode mode) {
    }

    record Budget(@Describe(value = "minutes at most", optional = true) Integer minutes) {
    }

    /** A run's main, never started here: a public class's, as a run's must be. */
    public static final class Main {
        public static void main(String[] args) {
        }
    }

    @Test
    void eachPluginListsItsToolsByTheirMcpNameAndItsRunsWithTheirSchemas() throws Exception {
        AssistantTool<Look> look = AssistantTool.named("look").describedAs("Look at the game.").takes(Look.class)
                .handledBy((sent, context) -> AgentReply.text("seen"));
        RunEntry explore = RunEntry.of("Keep exploring", Main::main).takes(Budget.class);
        Operations.Listed sdk = new Operations.Listed(new PluginCoordinate("com.github.BotMakerDev", "botmaker-sdk"),
                "BotMaker SDK", "1.4.0", List.of(look), List.of(explore));

        JsonNode answer = JSON.readTree(Operations.answer(Optional.of(Path.of("/bots/farm")), List.of(sdk)));

        assertEquals("/bots/farm", answer.path("project").asText());
        JsonNode plugin = answer.at("/plugins/0");
        assertEquals("botmaker-sdk", plugin.path("artifactId").asText());
        assertEquals("1.4.0", plugin.path("version").asText());
        JsonNode tool = plugin.at("/operations/0");
        assertEquals("tool", tool.path("kind").asText());
        assertEquals("botmaker_sdk_look", tool.path("call").asText(), "called by the name the MCP serves it under");
        assertEquals("[\"quick\",\"full\"]", tool.at("/schema/properties/mode/enum").toString());
        assertEquals("[\"what\"]", tool.at("/schema/required").toString());
        JsonNode run = plugin.at("/operations/1");
        assertEquals("run", run.path("kind").asText());
        assertEquals("Keep exploring", run.path("name").asText());
        assertFalse(run.has("call"), "a run is started by run_bot");
        assertEquals("integer", run.at("/schema/properties/minutes/type").asText());
    }

    @Test
    void withNoProjectOpenTheProjectIsNullAndAToolNameTakenEarlierIsLeftOut() throws Exception {
        AssistantTool<AssistantTool.None> look = AssistantTool.named("look").describedAs("Look.").takesNothing()
                .handledBy((sent, context) -> AgentReply.text("seen"));
        Operations.Listed first = new Operations.Listed(new PluginCoordinate("a.one", "web"), "Web", "1.0.0",
                List.of(look), List.of());
        Operations.Listed second = new Operations.Listed(new PluginCoordinate("b.two", "web"), "Other web", "2.0.0",
                List.of(look), List.of());

        JsonNode answer = JSON.readTree(Operations.answer(Optional.empty(), List.of(first, second)));

        assertTrue(answer.path("project").isNull());
        assertEquals(1, answer.at("/plugins/0/operations").size());
        assertEquals(0, answer.at("/plugins/1/operations").size(), "the MCP serves web_look once, the first's");
    }
}
