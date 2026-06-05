package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import org.lwjgl.glfw.GLFW;

import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffChannel;
import net.rainbowcreation.vocanicz.minegit.mod.world.DimensionMapping;

/**
 * The loader-agnostic client overlay wiring (issue #80, Spec C §2.3/§3). Installs everything that
 * Architectury can host commonly — the receive sink, the toggle keybind, the HUD, the per-tick
 * lifecycle (dimension tracking + auto-expire), and disconnect clearing — and delegates the one
 * loader-specific seam (world render) to {@link OverlayRenderHook}. Driven once from each loader's
 * client init.
 *
 * <p>State lives in the singleton {@link OverlayClientState#CLIENT}; this class is the glue that feeds
 * it. Client-only — never reached on a dedicated server.
 */
@Environment(EnvType.CLIENT)
public final class OverlayClientHooks {

    /**
     * The active client config. Defaults until {@link #init} reads the on-disk file (issue #81);
     * the keybind/HUD/render all read it through {@link #config()} so a reload would be picked up.
     */
    private static volatile OverlayConfig config = OverlayConfig.defaults();

    /** Monotonic client tick, the {@code receivedAt}/expiry clock for {@link OverlayClientState}. */
    private static volatile long clientTick;

    private static KeyMapping toggleKey;

    private OverlayClientHooks() {
    }

    /** The active client overlay config. */
    public static OverlayConfig config() {
        return config;
    }

    /** The current client tick — the world-render hook stamps expiry decisions against it. */
    public static long clientTick() {
        return clientTick;
    }

    /** Wires the receive sink, keybind, HUD, lifecycle tick, disconnect, and world-render hook. */
    public static void init() {
        // Config: read the on-disk file once on init (writing a default template if absent), so the
        // tunables (notably renderCap + autoExpireSeconds) actually change overlay behavior.
        config = OverlayConfigFile.load(Platform.getConfigFolder().resolve(OverlayConfigFile.FILE_NAME));

        // Receive: each reassembled-frame's bytes build/replace the held overlay, stamped with the
        // current tick (the expiry baseline). The per-loader receiver funnels here via DiffChannel.
        DiffChannel.setClientHandler(bytes -> OverlayClientState.CLIENT.acceptFrame(bytes, clientTick));

        // Keybind (default J): toggles visibility of the held overlay.
        toggleKey = new KeyMapping(
                "key.minegit.toggle_overlay", GLFW.GLFW_KEY_J, KeyMapping.Category.MISC);
        KeyMappingRegistry.register(toggleKey);

        // Per-tick lifecycle: advance the clock, track the active dimension (a change auto-clears),
        // drain keybind presses, and auto-expire a forgotten overlay.
        ClientTickEvent.CLIENT_POST.register(OverlayClientHooks::onClientTick);

        // Disconnect clears the held overlay and forgets the dimension.
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> OverlayClientState.CLIENT.onDisconnect());

        // HUD: +N -M ~K (+J more).
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) -> OverlayHud.render(graphics, config));

        // World render (loader-specific seam): translucent boxes after the translucent pass.
        OverlayRenderHook.register();
    }

    private static void onClientTick(Minecraft client) {
        clientTick++;

        if (client.level != null) {
            DimensionId dim = DimensionMapping.fromKey(client.level.dimension().identifier().toString());
            OverlayClientState.CLIENT.setActiveDimension(dim);
        }

        if (toggleKey != null) {
            while (toggleKey.consumeClick()) {
                OverlayClientState.CLIENT.toggle();
            }
        }

        OverlayClientState.CLIENT.tickExpiry(clientTick, config.lifetimeTicks());
    }
}
