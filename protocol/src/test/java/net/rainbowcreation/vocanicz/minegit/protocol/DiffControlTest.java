package net.rainbowcreation.vocanicz.minegit.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The 1-byte {@code minegit:diffsub} control codec (Spec C batch 2 §2.1, issue #91): the client→server
 * SUBSCRIBE/UNSUBSCRIBE message the keybind drives. Pins the wire bytes ({@code 0 = UNSUBSCRIBE},
 * {@code 1 = SUBSCRIBE}), the round-trip, and clean rejection of a malformed byte/length.
 */
class DiffControlTest {

    @Test
    void wireBytesAreStableAndDocumented() {
        assertEquals(0, DiffControl.UNSUBSCRIBE.wireByte(), "UNSUBSCRIBE must stay byte 0");
        assertEquals(1, DiffControl.SUBSCRIBE.wireByte(), "SUBSCRIBE must stay byte 1");
    }

    @Test
    void encodeIsExactlyOneByte() {
        assertArrayEquals(new byte[] {0}, DiffControl.UNSUBSCRIBE.encode());
        assertArrayEquals(new byte[] {1}, DiffControl.SUBSCRIBE.encode());
    }

    @Test
    void roundTripsThroughTheCodec() {
        for (DiffControl control : DiffControl.values()) {
            assertSame(control, DiffControl.decode(control.encode()),
                    control + " must survive encode→decode");
        }
    }

    @Test
    void fromByteMapsBothKnownCodes() {
        assertSame(DiffControl.UNSUBSCRIBE, DiffControl.fromByte(0));
        assertSame(DiffControl.SUBSCRIBE, DiffControl.fromByte(1));
    }

    @Test
    void unknownByteIsRejectedCleanly() {
        assertThrows(IllegalArgumentException.class, () -> DiffControl.fromByte(2));
        assertThrows(IllegalArgumentException.class, () -> DiffControl.fromByte(255));
        assertThrows(IllegalArgumentException.class, () -> DiffControl.decode(new byte[] {2}));
    }

    @Test
    void wrongLengthPayloadIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DiffControl.decode(new byte[] {}));
        assertThrows(IllegalArgumentException.class, () -> DiffControl.decode(new byte[] {1, 0}));
    }

    @Test
    void decodeRejectsNull() {
        assertThrows(NullPointerException.class, () -> DiffControl.decode(null));
    }
}
