package com.botmaker.core.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreLauncherTest {

    @TempDir
    Path dir;

    private final List<CoreMain.Running> cores = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopCores() {
        cores.forEach(CoreMain.Running::close);
    }

    @Test
    void twoStartersOneCore() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CoreLauncher.Starter starter = d -> {
            starts.incrementAndGet();
            start(d, "1.0");
        };
        ExecutorService two = Executors.newFixedThreadPool(2);
        try {
            List<Future<CoreLauncher.Address>> found = two.invokeAll(List.of(
                    () -> { ready.countDown(); ready.await(); return CoreLauncher.connect(dir, "1.0", starter); },
                    () -> { ready.countDown(); ready.await(); return CoreLauncher.connect(dir, "1.0", starter); }));
            assertEquals(1, starts.get());
            assertEquals(found.get(0).get().port(), found.get(1).get().port());
        } finally {
            two.shutdownNow();
        }
    }

    @Test
    void anOlderCoreWithNoClientQuitsForTheNewOne() throws Exception {
        CountDownLatch oldEnded = new CountDownLatch(1);
        CoreMain.Running old = CoreMain.serve(dir, "1.0", CoreServer.Idle.DEFAULT, oldEnded::countDown);
        CoreLauncher.Address at = CoreLauncher.connect(dir, "2.0", d -> start(d, "2.0"));
        assertTrue(oldEnded.await(5, TimeUnit.SECONDS));
        assertNotEquals(old.server().port(), at.port());
        assertEquals("2.0", CoreLauncher.info(at).orElseThrow().version());
    }

    @Test
    void anOlderCoreInUseRefusesTheNewClientNamingBoth() throws Exception {
        CoreMain.Running old = start(dir, "1.0");
        CoreLauncher.Address at = CoreLauncher.connect(dir, "1.0", d -> { throw new AssertionError("started"); });
        try (ChannelClient attached = ChannelClient.open(at, "1.0", m -> { }, () -> { })) {
            CoreLauncher.Refused refused = assertThrows(CoreLauncher.Refused.class,
                    () -> CoreLauncher.connect(dir, "2.0", d -> { throw new AssertionError("started"); }));
            assertTrue(refused.getMessage().contains("1.0") && refused.getMessage().contains("2.0"),
                    refused.getMessage());
            assertTrue(CoreLauncher.info(at).isPresent(), "the old core keeps running");
        }
        assertEquals(old.server().port(), at.port());
    }

    @Test
    void aKilledCoresFileIsPassedOver() throws Exception {
        new CoreFile("1.0", 999_999, freePort()).write(dir);
        AtomicInteger starts = new AtomicInteger();
        CoreLauncher.Address at = CoreLauncher.connect(dir, "1.0", d -> {
            starts.incrementAndGet();
            start(d, "1.0");
        });
        assertEquals(1, starts.get());
        assertEquals("1.0", CoreLauncher.info(at).orElseThrow().version());
    }

    @Test
    void aCoreThatDiesAsItStartsFailsAtOnce() {
        CoreLauncher.Starter dies = new CoreLauncher.Starter() {
            @Override
            public void start(Path d) {
            }

            @Override
            public boolean ended() {
                return true;
            }
        };
        long before = System.nanoTime();
        IOException failed = assertThrows(IOException.class, () -> CoreLauncher.connect(dir, "1.0", dies));
        assertTrue(failed.getMessage().contains("core.log"), failed.getMessage());
        assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(5), "no wait for a dead core");
    }

    private CoreMain.Running start(Path d, String version) {
        try {
            CoreMain.Running core = CoreMain.serve(d, version, CoreServer.Idle.DEFAULT, () -> { });
            cores.add(core);
            return core;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
