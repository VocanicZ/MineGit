package net.rainbowcreation.vocanicz.minegit.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolTest {

    @Test
    void exposesDiffChannelConstant() {
        assertEquals("minegit:diff", Protocol.DIFF_CHANNEL);
    }

    @Test
    void exposesDiffControlChannelConstant() {
        assertEquals("minegit:diffsub", Protocol.DIFF_CONTROL_CHANNEL);
    }
}
