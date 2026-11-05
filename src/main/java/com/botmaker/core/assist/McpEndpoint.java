package com.botmaker.core.assist;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;

/**
 * Where an MCP client — Claude Code, Cursor, Codex, Gemini CLI, opencode, a local runner — reaches BotMaker, and how
 * that address is guarded. The core's server ({@code server.CoreServer}) serves it since plan phase 10d, on
 * {@link McpConfig}'s port: {@link McpTools#own} over the open bot, and the tools Studio registers over its channel.
 *
 * <p><b>Local and authenticated.</b> Bound to {@code 127.0.0.1} only, and every request must carry
 * {@code Authorization: Bearer <token>}: a loopback port is reachable by every process on the machine and, via
 * DNS rebinding, by a web page in its browser, and this one can rewrite the user's code. The token is
 * {@link McpConfig}'s, in a file only the user can read.
 */
public final class McpEndpoint {

    public static final String PATH = "/mcp";
    /**
     * Where a client proves this is BotMaker before it sends the token: a process that took the port while the core was
     * down would be handed it otherwise. {@code ?nonce=<hex>} answers HMAC-SHA256 of the nonce under the token, in hex;
     * the only path the token is not asked for, and it says nothing of the token.
     */
    public static final String HELLO = "/mcp-hello";

    private McpEndpoint() {
    }

    /** What a client is pointed at, on {@code port}. */
    public static String url(int port) {
        return "http://127.0.0.1:" + port + PATH;
    }

    /** Answers {@link #HELLO}: the nonce signed with the token, so a client knows it talks to this BotMaker. */
    private static final class Hello extends HttpServlet {
        private final String token;

        Hello(String token) {
            this.token = token;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String nonce = request.getParameter("nonce");
            if (nonce == null || !nonce.matches("[0-9a-f]{32,128}")) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            response.setContentType("text/plain");
            response.getWriter().write(proof(token, request.getLocalPort(), nonce));
        }
    }

    /** Puts {@code context} behind {@code token}: {@link #HELLO} answers the proof, and every other path refuses a request without the bearer token. */
    public static void guard(ServletContextHandler context, String token) {
        context.addServlet(new ServletHolder(new Hello(token)), HELLO);
        context.addFilter(new FilterHolder(bearer(token)), "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    /**
     * HMAC-SHA256 of {@code <port>:<nonce>} under {@code token}, in hex: what {@link #HELLO} answers on {@code port}. The
     * port is the one the client dialled, so a proof another endpoint holding the token gave cannot be relayed from a
     * port that endpoint does not serve.
     */
    public static String proof(String token, int port, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((port + ":" + nonce).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is missing from this Java", e);
        }
    }

    /** Refuses a request whose bearer token is not {@code token}, compared in constant time; {@link #HELLO} aside. */
    private static Filter bearer(String token) {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return (request, response, chain) -> {
            if (HELLO.equals(((HttpServletRequest) request).getRequestURI())) {
                chain.doFilter(request, response);
                return;
            }
            String header = ((HttpServletRequest) request).getHeader("Authorization");
            byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, given)) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
        };
    }
}
