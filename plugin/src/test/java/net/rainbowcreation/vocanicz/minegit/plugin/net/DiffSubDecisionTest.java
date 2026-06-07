package net.rainbowcreation.vocanicz.minegit.plugin.net;

import net.rainbowcreation.vocanicz.minegit.plugin.net.DiffSubDecision.Decision;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class DiffSubDecisionTest {

    // DiffControl.encode() returns a 1-byte array; use it to build canonical wire bytes
    private static byte[] subBytes() {
        return DiffControl.SUBSCRIBE.encode();
    }

    private static byte[] unsubBytes() {
        return DiffControl.UNSUBSCRIBE.encode();
    }

    @Test
    void permittedSubscribe_pushes() {
        assertEquals(Decision.SUBSCRIBE_PUSH, DiffSubDecision.decide(subBytes(), () -> true));
    }

    @Test
    void unpermittedSubscribe_ignored() {
        assertEquals(Decision.IGNORE, DiffSubDecision.decide(subBytes(), () -> false));
    }

    @Test
    void unsubscribe_ungated() {
        // UNSUBSCRIBE bypasses the permission check — even when !permitted
        assertEquals(Decision.UNSUBSCRIBE, DiffSubDecision.decide(unsubBytes(), () -> false));
    }

    @Test
    void malformed_dropsNoThrow() {
        // 0xFF is an unknown DiffControl byte — decode() throws IllegalArgumentException
        assertEquals(Decision.DROP, DiffSubDecision.decide(new byte[]{(byte) 0xFF, 0x01}, () -> true));
    }

    @Test
    void emptyBytes_drops() {
        // empty payload: decode() throws IllegalArgumentException (wrong length)
        assertEquals(Decision.DROP, DiffSubDecision.decide(new byte[0], () -> true));
    }
}
