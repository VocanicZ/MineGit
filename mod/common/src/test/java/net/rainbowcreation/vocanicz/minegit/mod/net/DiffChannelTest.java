package net.rainbowcreation.vocanicz.minegit.mod.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Loader-agnostic half of the {@code minegit:diff} plumbing (issue #77): the client-side sink that
 * the per-loader receive registration funnels bytes into. The actual packet registration is
 * {@code @ExpectPlatform} loader code, proven by {@code runClient} launch + a both-loader GameTest;
 * here we pin the pure dispatch — install a sink, deliver bytes, observe them — which is what
 * "round-trips bytes to the client handler" reduces to once the wire bytes are decoded.
 */
final class DiffChannelTest {

    @AfterEach
    void reset() {
        DiffChannel.resetClientHandler();
    }

    @Test
    void deliverToClientHandsBytesToInstalledHandler() {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        DiffChannel.setClientHandler(seen::set);

        byte[] payload = {1, 2, 3, 4, 5};
        DiffChannel.deliverToClient(payload);

        assertArrayEquals(payload, seen.get(), "the installed handler must receive the delivered bytes");
    }

    @Test
    void laterHandlerReplacesEarlierOne() {
        AtomicReference<byte[]> first = new AtomicReference<>();
        AtomicReference<byte[]> second = new AtomicReference<>();
        DiffChannel.setClientHandler(first::set);
        DiffChannel.setClientHandler(second::set);

        DiffChannel.deliverToClient(new byte[] {9});

        assertSame(null, first.get(), "the replaced handler must no longer receive bytes");
        assertArrayEquals(new byte[] {9}, second.get(), "the latest handler receives the bytes");
    }

    @Test
    void defaultHandlerIsANoOpThatDoesNotThrow() {
        // No handler installed (just reset): delivery must be a safe no-op, not an NPE — the wire is
        // open before the client overlay (a later batch) installs its real sink.
        DiffChannel.deliverToClient(new byte[] {7, 7});
    }

    @Test
    void setClientHandlerRejectsNull() {
        assertThrows(NullPointerException.class, () -> DiffChannel.setClientHandler(null));
    }
}
