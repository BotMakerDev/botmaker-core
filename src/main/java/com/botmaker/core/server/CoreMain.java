package com.botmaker.core.server;

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
        Running core = serve(dir, AppVersion.label(), CoreServer.Idle.DEFAULT, ended::countDown);
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
     * Serves for {@code dir} until idle or asked to quit, then closes and runs {@code ended}; in-process for tests,
     * whose {@code ended} does not exit the JVM.
     */
    public static Running serve(Path dir, String version, CoreServer.Idle idle, Runnable ended) throws Exception {
        return serve(dir, version, idle, OpenProject.Libraries.MAVEN, ended);
    }

    /** {@link #serve(Path, String, CoreServer.Idle, Runnable)}, opening projects over {@code libraries}. */
    public static Running serve(Path dir, String version, CoreServer.Idle idle, OpenProject.Libraries libraries,
                                Runnable ended) throws Exception {
        String token = CoreLauncher.token(dir);
        Running[] self = new Running[1];
        CountDownLatch started = new CountDownLatch(1);
        Projects projects = new Projects(libraries);
        CoreServer server = CoreServer.start(0, token, version, new CoreServer.Hooks(idle, () -> false,
                projects::attach, () -> {
                    awaitQuietly(started);
                    self[0].close();
                    ended.run();
                }));
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
