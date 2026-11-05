package com.botmaker.core.server;

import com.botmaker.core.assist.McpConfig;
import com.botmaker.core.config.AppVersion;
import com.botmaker.shared.config.CacheDirs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * The core's process: {@code java -cp <Studio's classpath> com.botmaker.core.server.CoreMain [--dir <cache dir>]},
 * as {@link CoreLauncher#process} starts it. It serves on a free port of {@code 127.0.0.1}, says so in its
 * {@link CoreFile}, and ends when idle or asked to quit, removing the file.
 */
public final class CoreMain {

    public static final String DIR = "--dir";

    private CoreMain() {}

    public static void main(String[] args) throws Exception {
        Path dir = CacheDirs.cacheRoot();
        for (int i = 0; i + 1 < args.length; i++) {
            if (DIR.equals(args[i])) dir = Path.of(args[++i]);
        }
        CountDownLatch ended = new CountDownLatch(1);
        Running core = serveOnMcpPort(dir, ended::countDown);
        Runtime.getRuntime().addShutdownHook(new Thread(core::close, "core-shutdown"));
        System.out.println("BotMaker's core " + AppVersion.label() + " serves on port " + core.server().port() + ".");
        ended.await();
        System.exit(0);
    }

    /** A core serving for {@code dir}; {@link #close} ends it as an idle exit would. */
    public record Running(CoreServer server, CoreFile file, Path dir, Projects projects) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
            projects.close();
            file.removeFrom(dir);
        }
    }

    /**
     * The user's core: on the MCP's port ({@link McpConfig#port}, plan phase 10d), so an MCP client pointed there talks
     * to it; on a free port when another process holds that one, said on the error stream.
     */
    private static Running serveOnMcpPort(Path dir, Runnable ended) throws Exception {
        int port = McpConfig.read(dir.resolve(CoreLauncher.TOKEN_FILE)).port();
        try {
            return serve(dir, port, AppVersion.label(), CoreServer.Idle.DEFAULT, OpenProject.Libraries.MAVEN, ended);
        } catch (IOException taken) {
            System.err.println("Port " + port + " is taken, so MCP clients set up for it will not reach the core: "
                    + taken.getMessage());
            return serve(dir, AppVersion.label(), CoreServer.Idle.DEFAULT, ended);
        }
    }

    /**
     * Serves for {@code dir} on a free port until idle or asked to quit, then closes and runs {@code ended};
     * in-process for tests, whose {@code ended} does not exit the JVM.
     */
    public static Running serve(Path dir, String version, CoreServer.Idle idle, Runnable ended) throws Exception {
        return serve(dir, version, idle, OpenProject.Libraries.MAVEN, ended);
    }

    /** {@link #serve(Path, String, CoreServer.Idle, Runnable)}, opening projects over {@code libraries}. */
    public static Running serve(Path dir, String version, CoreServer.Idle idle, OpenProject.Libraries libraries,
                                Runnable ended) throws Exception {
        return serve(dir, 0, version, idle, libraries, ended);
    }

    /** As {@link #serve(Path, String, CoreServer.Idle, OpenProject.Libraries, Runnable)}, on {@code port}. */
    static Running serve(Path dir, int port, String version, CoreServer.Idle idle, OpenProject.Libraries libraries,
                         Runnable ended) throws Exception {
        String token = CoreLauncher.token(dir);
        Running[] self = new Running[1];
        CountDownLatch started = new CountDownLatch(1);
        ServedTools tools = new ServedTools();
        Projects projects = new Projects(libraries, tools);
        CoreServer server;
        try {
            // A bot kept running, or started through the MCP, keeps the core up with no client (plan phase 11).
            server = CoreServer.start(port, token, version, new CoreServer.Hooks(idle, projects::busy,
                    projects::attach, () -> {
                        awaitQuietly(started);
                        self[0].close();
                        ended.run();
                    }));
        } catch (IOException e) {
            projects.close();
            throw e;
        } catch (Exception e) {
            projects.close();
            throw new IOException(e.getMessage(), e);
        }
        tools.serveOn(server.mcp());
        CoreFile file = new CoreFile(version, ProcessHandle.current().pid(), server.port());
        try {
            file.write(dir);
        } catch (IOException e) {
            server.close();
            throw e;
        }
        self[0] = new Running(server, file, dir, projects);
        started.countDown();
        return self[0];
    }

    private static void awaitQuietly(CountDownLatch started) {
        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
