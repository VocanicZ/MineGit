package net.rainbowcreation.vocanicz.minegit.mod.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.rainbowcreation.vocanicz.minegit.mod.MineGitInfo;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.OverlayClientHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge client-distribution init (issue #77). Invoked from the {@code MineGitNeoForge} constructor
 * only when {@code Dist.isClient()} is true, so the dedicated server never reaches it — the seam where
 * the diff-overlay keybind / world-render / HUD hooks land in later batches.
 *
 * <p>The {@code minegit:diff} S2C receiver itself is registered commonly via
 * {@link MineGitNeoForgeNetworking} (NeoForge registers payload handlers on the shared payload event);
 * its handler only fires client-side. So for this batch the client init has nothing to wire beyond
 * proving the entrypoint exists and runs on the client.
 */
public final class MineGitNeoForgeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(MineGitInfo.MOD_NAME);

    private MineGitNeoForgeClient() {
    }

    public static void init(IEventBus modEventBus) {
        LOGGER.info("[{}] client-dist init — minegit:diff overlay receiver active", MineGitInfo.MOD_NAME);
        // Install the overlay sink + keybind + HUD + lifecycle + world-render hook (issue #80). The
        // S2C receiver registered in MineGitNeoForgeNetworking funnels bytes to
        // DiffChannel.deliverToClient, which the sink installed here turns into the held OverlayState.
        OverlayClientHooks.init();
    }
}
