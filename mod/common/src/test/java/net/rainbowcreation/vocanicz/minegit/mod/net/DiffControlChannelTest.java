package net.rainbowcreation.vocanicz.minegit.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Loader-agnostic half of the {@code minegit:diffsub} server-receive plumbing (issue #91): the
 * server-side handler sink that the per-loader receive registration funnels control bytes into. The
 * actual packet registration is loader code, proven by a both-loader GameTest; here we pin the pure
 * dispatch — install a handler, deliver bytes, observe {@code (player, SUB|UNSUB)} — which is the seam
 * the live-subscription loop (#E) consumes.
 *
 * <p>The handler is never dereferenced by the channel, so {@code null} stands in for the real
 * {@link ServerPlayer} the dedicated server would supply.
 */
final class DiffControlChannelTest {

    @AfterEach
    void reset() {
        DiffControlChannel.resetServerHandler();
    }

    @Test
    void deliverDecodesAndDispatchesSubscribe() {
        AtomicReference<DiffControl> seen = new AtomicReference<>();
        DiffControlChannel.setServerHandler((player, control) -> seen.set(control));

        DiffControlChannel.deliverToServer(null, DiffControl.SUBSCRIBE.encode());

        assertSame(DiffControl.SUBSCRIBE, seen.get(), "the handler must receive the decoded control");
    }

    @Test
    void deliverDecodesAndDispatchesUnsubscribe() {
        AtomicReference<DiffControl> seen = new AtomicReference<>();
        DiffControlChannel.setServerHandler((player, control) -> seen.set(control));

        DiffControlChannel.deliverToServer(null, DiffControl.UNSUBSCRIBE.encode());

        assertSame(DiffControl.UNSUBSCRIBE, seen.get());
    }

    @Test
    void deliverPassesThroughTheSendingPlayer() {
        AtomicReference<ServerPlayer> seenPlayer = new AtomicReference<>();
        AtomicReference<DiffControl> seenControl = new AtomicReference<>();
        DiffControlChannel.setServerHandler((player, control) -> {
            seenPlayer.set(player);
            seenControl.set(control);
        });

        // null is a valid stand-in here: the channel forwards the reference verbatim, never derefs it.
        DiffControlChannel.deliverToServer(null, DiffControl.SUBSCRIBE.encode());

        assertNull(seenPlayer.get(), "the channel forwards the sending player verbatim");
        assertSame(DiffControl.SUBSCRIBE, seenControl.get());
    }

    @Test
    void laterHandlerReplacesEarlierOne() {
        AtomicReference<DiffControl> first = new AtomicReference<>();
        AtomicReference<DiffControl> second = new AtomicReference<>();
        DiffControlChannel.setServerHandler((p, c) -> first.set(c));
        DiffControlChannel.setServerHandler((p, c) -> second.set(c));

        DiffControlChannel.deliverToServer(null, DiffControl.SUBSCRIBE.encode());

        assertNull(first.get(), "the replaced handler must no longer receive controls");
        assertSame(DiffControl.SUBSCRIBE, second.get(), "the latest handler receives the control");
    }

    @Test
    void defaultHandlerIsANoOpThatDoesNotThrow() {
        // No handler installed (just reset): delivery must be a safe no-op, not an NPE — the wire is
        // open before the live-subscription loop (a later issue) installs its real handler.
        DiffControlChannel.deliverToServer(null, DiffControl.SUBSCRIBE.encode());
    }

    @Test
    void malformedBytesAreSwallowedAndNeverReachTheHandler() {
        AtomicReference<DiffControl> seen = new AtomicReference<>();
        DiffControlChannel.setServerHandler((p, c) -> seen.set(c));

        // A hostile/old client could send junk; the server must not crash and must not dispatch it.
        DiffControlChannel.deliverToServer(null, new byte[] {7});      // unknown byte
        DiffControlChannel.deliverToServer(null, new byte[] {});       // empty
        DiffControlChannel.deliverToServer(null, new byte[] {1, 1});   // too long

        assertNull(seen.get(), "a malformed control must not reach the handler");
    }

    @Test
    void setServerHandlerRejectsNull() {
        assertThrows(NullPointerException.class, () -> DiffControlChannel.setServerHandler(null));
    }

    @Test
    void countsControlsAcrossDeliveries() {
        AtomicReference<Integer> count = new AtomicReference<>(0);
        DiffControlChannel.setServerHandler((p, c) -> count.set(count.get() + 1));

        DiffControlChannel.deliverToServer(null, DiffControl.SUBSCRIBE.encode());
        DiffControlChannel.deliverToServer(null, DiffControl.UNSUBSCRIBE.encode());
        DiffControlChannel.deliverToServer(null, new byte[] {99}); // malformed: not counted

        assertEquals(2, count.get(), "only well-formed controls dispatch to the handler");
        assertTrue(count.get() <= 2);
    }
}
