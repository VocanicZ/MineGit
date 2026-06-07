package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Headless tests for the overlay config defaults, parsing, clamping, and tick conversion (§3.1). */
class OverlayConfigTest {

    @Test
    void defaultsMatchSpec() {
        OverlayConfig c = OverlayConfig.defaults();
        assertEquals("J", c.getKeybind());
        assertEquals(64.0, c.getMaxRenderDistance());
        assertEquals(4096, c.getRenderCap());
        assertEquals(60, c.getAutoExpireSeconds());
        assertEquals(10, c.getLiveRefreshTicks());
        assertEquals(OverlayConfig.HudCorner.TOP_LEFT, c.getHudCorner());
    }

    @Test
    void liveRefreshTicksParsesAndDefaultsToTen() {
        assertEquals(10, OverlayConfig.fromProperties(new HashMap<String, String>()).getLiveRefreshTicks());
        Map<String, String> props = new HashMap<String, String>();
        props.put("liveRefreshTicks", "5");
        assertEquals(5, OverlayConfig.fromProperties(props).getLiveRefreshTicks());
    }

    @Test
    void liveRefreshTicksClampsToAtLeastOne() {
        // A refresh cadence < 1 tick is meaningless; OverlayConfig clamps it up to 1.
        OverlayConfig zero = new OverlayConfig("J", 64, 4096, 60, 0, OverlayConfig.HudCorner.TOP_LEFT);
        assertEquals(1, zero.getLiveRefreshTicks());
        OverlayConfig neg = new OverlayConfig("J", 64, 4096, 60, -7, OverlayConfig.HudCorner.TOP_LEFT);
        assertEquals(1, neg.getLiveRefreshTicks());
    }

    @Test
    void autoExpireSecondsConvertToLifetimeTicks() {
        assertEquals(1200L, OverlayConfig.defaults().lifetimeTicks(), "60s * 20 ticks");
        OverlayConfig disabled = new OverlayConfig("J", 64, 4096, 0, OverlayConfig.HudCorner.TOP_LEFT);
        assertEquals(0L, disabled.lifetimeTicks(), "0s disables the timer");
    }

    @Test
    void missingKeysFallBackToDefaults() {
        OverlayConfig c = OverlayConfig.fromProperties(new HashMap<String, String>());
        assertEquals("J", c.getKeybind());
        assertEquals(64.0, c.getMaxRenderDistance());
        assertEquals(4096, c.getRenderCap());
        assertEquals(60, c.getAutoExpireSeconds());
        assertEquals(OverlayConfig.HudCorner.TOP_LEFT, c.getHudCorner());
    }

    @Test
    void propertiesParseAndHudCornerIsCaseAndDashInsensitive() {
        Map<String, String> props = new HashMap<String, String>();
        props.put("keybind", "K");
        props.put("maxRenderDistance", "128");
        props.put("renderCap", "1024");
        props.put("autoExpireSeconds", "30");
        props.put("hudCorner", "bottom-right");
        OverlayConfig c = OverlayConfig.fromProperties(props);
        assertEquals("K", c.getKeybind());
        assertEquals(128.0, c.getMaxRenderDistance());
        assertEquals(1024, c.getRenderCap());
        assertEquals(30, c.getAutoExpireSeconds());
        assertEquals(OverlayConfig.HudCorner.BOTTOM_RIGHT, c.getHudCorner());
        assertEquals(600L, c.lifetimeTicks());
    }

    @Test
    void unparseableValuesFallBackToDefaults() {
        Map<String, String> props = new HashMap<String, String>();
        props.put("renderCap", "not-a-number");
        props.put("autoExpireSeconds", "");
        props.put("hudCorner", "middle");
        OverlayConfig c = OverlayConfig.fromProperties(props);
        assertEquals(4096, c.getRenderCap());
        assertEquals(60, c.getAutoExpireSeconds());
        assertEquals(OverlayConfig.HudCorner.TOP_LEFT, c.getHudCorner());
    }

    @Test
    void negativeNumbersAreClampedToZero() {
        OverlayConfig c = new OverlayConfig("J", -5, -1, -10, OverlayConfig.HudCorner.TOP_LEFT);
        assertEquals(0.0, c.getMaxRenderDistance());
        assertEquals(0, c.getRenderCap());
        assertEquals(0, c.getAutoExpireSeconds());
    }
}
