package net.rainbowcreation.vocanicz.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import net.rainbowcreation.vocanicz.minegit.mod.net.LiveSubscriptionLoop;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.OverlayConfig;
import org.junit.jupiter.api.Test;

/**
 * Wiring test for issue #94: the configurable {@code liveRefreshTicks} (Spec C batch 2 §2.3, default
 * 10) must reach the server live loop so it governs the push cadence. The runtime builds its
 * {@link LiveSubscriptionLoop} from the configured value; this verifies the plumbing without booting a
 * {@code MinecraftServer} (the cadence behaviour itself is covered by {@code LiveSubscriptionLoopTest}).
 */
class ServerCommandRuntimeLiveRefreshTest {

    @Test
    void defaultRuntimeUsesTheTenTickDefault() {
        ServerCommandRuntime runtime = new ServerCommandRuntime();
        assertEquals(LiveSubscriptionLoop.DEFAULT_REFRESH_TICKS, runtime.liveRefreshTicks());
        assertEquals(OverlayConfig.DEFAULT_LIVE_REFRESH_TICKS, runtime.liveRefreshTicks());
    }

    @Test
    void configuredRefreshTicksReachTheLiveLoop() {
        ServerCommandRuntime runtime = new ServerCommandRuntime(Clock.systemUTC(), Runnable::run, 3);
        assertEquals(3, runtime.liveRefreshTicks());
    }
}
