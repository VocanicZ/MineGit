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
import net.minecraft.network.chat.Component;

import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffChannel;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel;
import net.rainbowcreation.vocanicz.minegit.mod.world.DimensionMapping;

/**
 * The loader-agnostic client overlay wiring (issue #80, Spec C §2.3/§3). Installs everything that
 * Architectury can host commonly — the receive sink, the subscription keybind, the HUD, the per-tick
 * lifecycle (dimension tracking), and disconnect clearing — and delegates the one loader-specific seam
 * (world render) to {@link OverlayRenderHook}. Driven once from each loader's client init.
 *
 * <p>State lives in the singleton {@link OverlayClientState#CLIENT}; this class is the glue that feeds
 * it. Client-only — never reached on a dedicated server.
 */
@Environment(EnvType.CLIENT)
public final class OverlayClientHooks {

    /**
     * Per-tick section budget for the live-diff engine — caps how many dirty/loaded chunk sections
     * {@link OverlayClientState#tickEngine} re-diffs each client tick so a large dirty backlog is
     * amortized over ticks rather than stalling the render thread.
     */
    private static final int SECTION_BUDGET = 8;

    /**
     * The active client config. Defaults until {@link #init} reads the on-disk file (issue #81);
     * the keybind/HUD/render all read it through {@link #config()} so a reload would be picked up.
     */
    private static volatile OverlayConfig config = OverlayConfig.defaults();

    /** Monotonic client tick, the {@code receivedAt}/expiry clock for {@link OverlayClientState}. */
    private static volatile long clientTick;

    private static KeyMapping toggleKey;

    /**
     * The keybind's subscription-control seam: encodes each {@link
     * net.rainbowcreation.vocanicz.minegit.protocol.DiffControl} and sends it to the server over the
     * {@code minegit:diffsub} channel (issue #91). Client-only — the {@code @ExpectPlatform} send is
     * stitched per loader.
     */
    private static final OverlayClientState.ControlSender CONTROL_SENDER =
            control -> DiffControlChannel.sendToServer(control.encode());

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

        // Live-diff engine seams (SP2 B2): read the live client world through ClientLevelAccess, and
        // feed the engine the client world's block-changes + chunk-loads so it re-diffs what moved.
        OverlayClientState.CLIENT.setLevelSupplier(ClientLevelAccess::current);
        ClientWorldHooks.register(
                (dim, x, y, z) -> OverlayClientState.CLIENT.onClientBlockChange(dim, x, y, z),
                (dim, pos) -> OverlayClientState.CLIENT.onClientChunkLoad(dim, pos));

        // Keybind (default J): toggles the live subscription — SUBSCRIBE/UNSUBSCRIBE over diffsub.
        toggleKey = new KeyMapping(
                "key.minegit.toggle_overlay", GLFW.GLFW_KEY_J, KeyMapping.Category.MISC);
        KeyMappingRegistry.register(toggleKey);

        // Per-tick lifecycle: advance the clock, track the active dimension (a change auto-clears),
        // and drain keybind presses into subscription toggles.
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
                // Only toggle when the server negotiated minegit:diffsub. On a plugin/vanilla server
                // the channel is absent — sending would throw (NeoForge) and crash the client, so
                // instead tell the player the overlay isn't available here and leave state untouched.
                if (!DiffControlChannel.canSendToServer()) {
                    notifyOverlayUnsupported(client);
                    continue;
                }
                OverlayClientState.CLIENT.toggleSubscription(CONTROL_SENDER);
            }
        }

        // Advance the live-diff engine: re-diff up to SECTION_BUDGET dirty sections this tick.
        OverlayClientState.CLIENT.tickEngine(SECTION_BUDGET);
    }

    /** Actionbar note when the keybind is pressed on a server that doesn't speak {@code minegit:diffsub}. */
    private static void notifyOverlayUnsupported(Minecraft client) {
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("[MineGit] Live diff overlay isn't available on this server."), true);
        }
    }
}
