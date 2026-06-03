package com.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DimensionIdTest {

    @Test
    void equalByValue() {
        assertEquals(new DimensionId("overworld"), new DimensionId("overworld"));
        assertEquals(new DimensionId("overworld").hashCode(),
                new DimensionId("overworld").hashCode());
    }

    @Test
    void unequalWhenIdDiffers() {
        assertNotEquals(new DimensionId("overworld"), new DimensionId("the_nether"));
    }

    @Test
    void exposesId() {
        assertEquals("custom:skylands", new DimensionId("custom:skylands").getId());
    }

    @Test
    void nullIdRejected() {
        assertThrows(NullPointerException.class, () -> new DimensionId(null));
    }

    @Test
    void vanillaConstantsHaveExpectedIds() {
        assertEquals("overworld", DimensionId.OVERWORLD.getId());
        assertEquals("the_nether", DimensionId.THE_NETHER.getId());
        assertEquals("the_end", DimensionId.THE_END.getId());
    }
}
