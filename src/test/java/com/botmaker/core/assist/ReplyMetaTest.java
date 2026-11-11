package com.botmaker.core.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A core tool's extras for a watcher: said during its call, they land on the reply's {@code _meta}. */
class ReplyMetaTest {

    record At(@Describe("x") int x, @Describe("y") int y) {
    }

    @Test
    void aTapSaidDuringTheCallIsOnTheRepliesMeta() {
        AssistantTool<At> tool = AssistantTool.named("touch").describedAs("Touches").takes(At.class)
                .handledBy((at, context) -> {
                    ReplyMeta.put(ReplyMeta.TAP, ReplyMeta.point(at.x(), at.y()));
                    return AgentReply.text("Tapped.");
                });
        CallToolResult result = call(McpTools.served(new JacksonMcpJsonMapper(new ObjectMapper()), tool),
                Map.of("x", 4, "y", 9));
        assertEquals(Map.of(ReplyMeta.TAP, Map.of("x", 4, "y", 9)), result.meta());
        assertEquals("Tapped.", ((TextContent) result.content().getFirst()).text());
    }

    @Test
    void aToolThatSaysNothingHasNoMetaAndAPutOutsideACallIsDropped() {
        ReplyMeta.put(ReplyMeta.TAP, ReplyMeta.point(1, 1));
        AssistantTool<At> tool = AssistantTool.named("look").describedAs("Looks").takes(At.class)
                .handledBy((at, context) -> AgentReply.text("Seen."));
        assertNull(call(McpTools.served(new JacksonMcpJsonMapper(new ObjectMapper()), tool), Map.of("x", 0, "y", 0))
                .meta());
    }

    private static CallToolResult call(SyncToolSpecification tool, Map<String, Object> arguments) {
        return tool.callHandler().apply(null, new CallToolRequest(tool.tool().name(), arguments));
    }
}
