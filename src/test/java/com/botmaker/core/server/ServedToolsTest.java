package com.botmaker.core.server;

import com.botmaker.core.assist.CoreHome;
import com.botmaker.core.assist.RunLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServedToolsTest {

    private final McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper());

    /**
     * Studio registers its own tools on attaching, which stand in for the core's home and run tools; it has no
     * {@code device}, so the core's must stay (click-test, 2026-11-11: the Assistant window's session had none).
     */
    @Test
    void theDeviceToolStaysServedWithAClientAttached() throws Exception {
        ServedTools tools = new ServedTools();
        CoreHome.Host host = (CoreHome.Host) Proxy.newProxyInstance(CoreHome.Host.class.getClassLoader(),
                new Class<?>[]{CoreHome.Host.class}, (proxy, method, args) -> null);
        try (RunLog log = new RunLog(Clock.systemUTC(), () -> false)) {
            tools.fallback(CoreHome.served(json, host, log));
            tools.held(CoreHome.device(json));
            tools.lasting(CoreHome.lasting(json, host));
            assertTrue(tools.chosen().containsKey("device"), "with no client");

            Channel studio = new Channel("studio", null, null);
            tools.register(studio, new Request.Tools(1, List.of(tool("run_bot"), tool("ui")), true));
            Set<String> served = tools.chosen().keySet();
            assertTrue(served.contains("device"), served::toString);
            assertTrue(served.containsAll(List.of("run_bot", "ui", "core_status", "quit_core")), served::toString);
            assertFalse(served.contains("list_projects"), "a home tool still gives way to the client's: " + served);
        }
    }

    private String tool(String name) throws Exception {
        return json.writeValueAsString(McpSchema.Tool.builder().name(name).description(name)
                .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null)).build());
    }
}
