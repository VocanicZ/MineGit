package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SectionAddrTest {

    @Test
    void packIsBijectiveOverLocalRange() {
        for (int dy = 0; dy < 16; dy++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int packed = SectionAddr.pack(dx, dy, dz);
                    assertEquals(dx, SectionAddr.localX(packed));
                    assertEquals(dy, SectionAddr.localY(packed));
                    assertEquals(dz, SectionAddr.localZ(packed));
                }
            }
        }
    }

    @Test
    void sectionYForBlockYFloorsTowardNegative() {
        assertEquals(0, SectionAddr.sectionY(0));
        assertEquals(0, SectionAddr.sectionY(15));
        assertEquals(1, SectionAddr.sectionY(16));
        assertEquals(-1, SectionAddr.sectionY(-1));
        assertEquals(-4, SectionAddr.sectionY(-64));
    }

    @Test
    void localFromWorldWrapsCorrectlyForNegativeCoords() {
        assertEquals(0, SectionAddr.local(0));
        assertEquals(15, SectionAddr.local(-1));
        assertEquals(0, SectionAddr.local(-16));
    }
}
