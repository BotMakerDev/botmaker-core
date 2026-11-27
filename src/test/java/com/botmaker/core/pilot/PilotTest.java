// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.core.pilot.transport.PilotTransport;
import com.botmaker.core.pilot.transport.TransportKind;
import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.shared.capture.NativeControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Pilot} over fake transports on loopback: the headless pairing check (a phone connects with the token of the
 * pairing link the state gives), and the rules a public way must keep.
 */
class PilotTest {

    /** A host's run that nothing watches. */
    private static final class IdleRuns implements Runs {
        @Override public void start() { }
        @Override public void stop() { }
        @Override public boolean isRunning() { return false; }
        @Override public OptionalLong pid() { return OptionalLong.empty(); }
        @Override public AutoCloseable onStateChanged(Consumer<Boolean> l) { return () -> { }; }
        @Override public AutoCloseable onTelemetry(Consumer<byte[]> l) { return () -> { }; }
        @Override public AutoCloseable onTrace(Consumer<TraceLine> l) { return () -> { }; }
    }

    /** A way to the phone that binds loopback and records what was done to it. */
    private static final class Loopback implements PilotTransport {
        final TransportKind kind;
        final boolean usable;
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        Loopback(TransportKind kind, boolean usable) {
            this.kind = kind;
            this.usable = usable;
        }

        @Override public TransportKind kind() { return kind; }
        @Override public Availability available() {
            return usable ? Availability.yes() : Availability.no(kind.id() + " is off", "turn it on");
        }
        @Override public String bindHost() { return "127.0.0.1"; }
        @Override public Opened open(int port) {
            opened.incrementAndGet();
            return new Opened("http://127.0.0.1:" + port, null);
        }
        @Override public void close() { closed.incrementAndGet(); }
    }

    @TempDir
    Path dir;

    private final Map<TransportKind, Loopback> ways = new EnumMap<>(TransportKind.class);
    private Pilot pilot;
    private PilotPreferences preferences;

    @BeforeEach
    void noRealInput() {
        NativeControllerFactory.setForTesting(new PilotFakes.RecordingController());
        RunViewsTest.Shows.open = false;
    }

    @AfterEach
    void tearDown() {
        if (pilot != null) pilot.close();
        NativeControllerFactory.setForTesting(null);
        RunViewsTest.Shows.open = true;
    }

    /** A pilot where only {@code usable} ways work. */
    private Pilot pilotWith(Set<TransportKind> usable) {
        preferences = PilotPreferences.in(dir.resolve(PilotPreferences.NAME));
        for (TransportKind kind : TransportKind.offered()) ways.put(kind, new Loopback(kind, usable.contains(kind)));
        pilot = new Pilot(preferences, new Pilot.Outside(ways::get,
                served -> new PilotServer(served.runs(), served.views(),
                        new PilotRoutes(() -> null, served.views()::shown, serial -> null),
                        new TargetCapture(null), new PilotControlService(served.runs()), preferences),
                List::of, () -> { }));
        return pilot;
    }

    private Pilot.Served served() {
        List<StudioPlugin> bound = List.of(new RunViewsTest.Plugin());
        return new Pilot.Served(new IdleRuns(), new RunViews(() -> bound, null, Runnable::run, () -> { }), dir);
    }

    @Test
    void aPhoneWithThePairingLinksTokenIsLetIn() throws Exception {
        pilotWith(EnumSet.of(TransportKind.LAN));
        PilotState on = pilot.start(TransportKind.LAN, served()).get(20, TimeUnit.SECONDS);

        assertEquals(PilotState.Phase.ON, on.phase());
        assertEquals(TransportKind.LAN, on.kind());
        assertNotNull(on.url());
        assertTrue(on.url().startsWith(on.baseUrl() + "/?token="));
        assertFalse(on.qr().isEmpty());
        assertFalse(on.toString().contains("token"), "a state's text is logged: it must not carry the link");

        String token = on.url().substring(on.url().indexOf("token=") + 6);
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(on.baseUrl().replace("http", "ws") + "/ws?token=" + token),
                        new WebSocket.Listener() {
                            @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                                heard.add(data.toString());
                                w.request(1);
                                return null;
                            }
                            @Override public void onOpen(WebSocket w) { w.request(1); }
                        })
                .get(5, TimeUnit.SECONDS);
        assertNotNull(heard.poll(10, TimeUnit.SECONDS), "a paired phone is told the pilot's state");
        assertEquals(1, pilot.state().phones().size());
        ws.abort();
    }

    @Test
    void aPublicWayIsNeverTheFallback() throws Exception {
        // Tailscale and the local network are down; Funnel works but was not asked for.
        pilotWith(EnumSet.of(TransportKind.FUNNEL, TransportKind.QUICK_TUNNEL));
        PilotState off = pilot.start(TransportKind.TAILNET, served()).get(20, TimeUnit.SECONDS);

        assertEquals(PilotState.Phase.OFF, off.phase());
        assertNotNull(off.problem());
        assertNull(off.url());
        assertEquals(0, ways.get(TransportKind.FUNNEL).opened.get());
        assertEquals(0, ways.get(TransportKind.QUICK_TUNNEL).opened.get());
        assertFalse(pilot.busy());
    }

    @Test
    void aWayThatIsDownFallsBackToADirectOne() throws Exception {
        pilotWith(EnumSet.of(TransportKind.LAN));
        PilotState on = pilot.start(TransportKind.TAILNET, served()).get(20, TimeUnit.SECONDS);

        assertEquals(PilotState.Phase.ON, on.phase());
        assertEquals(TransportKind.TAILNET, on.asked());
        assertEquals(TransportKind.LAN, on.kind());
    }

    @Test
    void stopTakesTheWayDownAndTheCoreIsFreeToQuit() throws Exception {
        pilotWith(EnumSet.of(TransportKind.LAN));
        pilot.start(TransportKind.LAN, served()).get(20, TimeUnit.SECONDS);
        assertTrue(pilot.busy());

        pilot.stop();

        assertEquals(PilotState.Phase.OFF, pilot.state().phase());
        assertEquals(1, ways.get(TransportKind.LAN).closed.get());
        assertFalse(pilot.busy());
    }

    @Test
    void aSecondStartOnTheSameWayKeepsTheServer() throws Exception {
        pilotWith(EnumSet.of(TransportKind.LAN));
        PilotState first = pilot.start(TransportKind.LAN, served()).get(20, TimeUnit.SECONDS);
        PilotState again = pilot.start(TransportKind.LAN, served()).get(20, TimeUnit.SECONDS);

        assertEquals(first.url(), again.url());
        assertEquals(1, ways.get(TransportKind.LAN).opened.get(), "a rebind would drop the paired phones");
    }

    @Test
    void resetRevokesTheTokenAndDisconnectsThePhones() throws Exception {
        pilotWith(EnumSet.of(TransportKind.LAN));
        PilotState on = pilot.start(TransportKind.LAN, served()).get(20, TimeUnit.SECONDS);
        CountDownLatch gone = new CountDownLatch(1);
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(on.baseUrl().replace("http", "ws") + "/ws?token="
                        + on.url().substring(on.url().indexOf("token=") + 6)), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket w) { w.request(10); }
                    @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                        gone.countDown();
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);

        pilot.resetToken();

        assertTrue(gone.await(10, TimeUnit.SECONDS), "a phone holding the old token is cut off");
        assertNotEquals(on.url(), pilot.state().url());
        ws.abort();
    }

    @Test
    void watchersHearEachChangeAndStopWhenClosed() throws Exception {
        pilotWith(EnumSet.of(TransportKind.LAN));
        List<PilotState.Phase> heard = new CopyOnWriteArrayList<>();
        AutoCloseable watching = pilot.watch(state -> heard.add(state.phase()));

        pilot.start(TransportKind.LAN, served()).get(20, TimeUnit.SECONDS);
        pilot.stop();
        Thread.sleep(300);
        watching.close();
        int before = heard.size();
        pilot.resetToken();
        Thread.sleep(300);

        assertEquals(PilotState.Phase.OFF, heard.getFirst(), "the state now comes first");
        assertTrue(heard.contains(PilotState.Phase.STARTING));
        assertTrue(heard.contains(PilotState.Phase.ON));
        assertEquals(PilotState.Phase.OFF, heard.getLast());
        assertEquals(before, heard.size());
    }

    @Test
    void attemptsNeverEndInAPublicWay() {
        for (TransportKind asked : TransportKind.values()) {
            for (TransportKind tried : Pilot.attempts(asked)) {
                assertTrue(tried == asked || !tried.publicInternet(), asked + " fell back to " + tried);
            }
        }
        assertEquals(List.of(TransportKind.FUNNEL, TransportKind.TAILNET, TransportKind.LAN),
                Pilot.attempts(TransportKind.FUNNEL));
    }

    @Test
    void preferencesKeepTheTokenAndTheWayInAFileOnlyTheOwnerReads() throws Exception {
        Path file = dir.resolve(PilotPreferences.NAME);
        PilotPreferences first = PilotPreferences.in(file);
        first.token("secret-token");
        first.transport(TransportKind.QUICK_TUNNEL);
        first.funnelOwner(42);

        PilotPreferences later = PilotPreferences.in(file);
        assertEquals("secret-token", later.token());
        assertEquals(TransportKind.QUICK_TUNNEL, later.transport());
        assertEquals(42, later.funnelOwner());
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void preferencesNeverThrowOnAnUnwritableFolder() {
        PilotPreferences broken = PilotPreferences.in(dir.resolve("no/such/dir").resolve(PilotPreferences.NAME));
        broken.token("a");
        assertEquals(TransportKind.TAILNET, broken.transport());
    }
}
