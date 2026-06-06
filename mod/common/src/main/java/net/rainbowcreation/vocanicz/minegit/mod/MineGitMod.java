package net.rainbowcreation.vocanicz.minegit.mod;

import net.rainbowcreation.vocanicz.minegit.mod.command.MineGitCommands;
import net.rainbowcreation.vocanicz.minegit.mod.command.ServerCommandRuntime;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel;
import net.rainbowcreation.vocanicz.minegit.mod.platform.Platform;
import net.rainbowcreation.vocanicz.minegit.mod.world.DirtyTracking;
import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.commands.CommandSourceStack;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared entrypoint, invoked from both loader entrypoints ({@code MineGitFabric#onInitialize} and
 * {@code MineGitNeoForge}'s constructor). Registers the {@code /minegit} command tree (plus the
 * {@code /mg} and {@code /git} aliases) on both loaders via Architectury's command-registration
 * event — the read/setup subcommands {@code init}, {@code status}, {@code log} (Spec D §4, issue #60).
 */
public final class MineGitMod {

    private static final Logger LOGGER = LoggerFactory.getLogger(MineGitInfo.MOD_NAME);

    /**
     * The one command runtime, shared across every {@code CommandRegistrationEvent} firing. The event
     * re-fires on each {@code /reload}, so building a fresh runtime per firing would leak the runtime's
     * single {@code minegit-git} daemon executor each time (issue #73). Built lazily and reused.
     */
    private static volatile ServerCommandRuntime sharedRuntime;

    private MineGitMod() {
    }

    public static void init() {
        // Touch a (relocated) JGit type so launch proves the bundled JGit is on the classpath and
        // links — the scaffold's "JGit present and loadable" acceptance, before any feature uses it.
        LOGGER.info(
                "[{}] initializing on {} — JGit bundled (object-id length {})",
                MineGitInfo.MOD_NAME,
                Platform.loaderName(),
                Constants.OBJECT_ID_STRING_LENGTH);
        // Publish the shared runtime's dirty-tracker registry so the setBlockState mixins (writers) and
        // the per-command adapters (readers) drive the SAME DirtyChunkSet instances. Done here, before
        // any command fires, so a block change before the first command still lands in the right set.
        DirtyTracking.install(sharedRuntime().trackers());
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> registerCommands(dispatcher));
        // Drain a slice of throttled commit/checkout work each server tick, so a whole-world scan
        // spreads over many ticks instead of freezing one ("Can't keep up!"). The same tick also
        // advances the live-overlay loop (#93). Fires on the server thread, where the queued level
        // reads/applies and the live recompute are safe.
        TickEvent.SERVER_POST.register(server -> sharedRuntime().tick(server));
        // Install the live-subscription handler over the minegit:diffsub control channel (#91→#93): a
        // SUBSCRIBE/UNSUBSCRIBE from a client toggles that player's live working-vs-HEAD push.
        DiffControlChannel.setServerHandler(sharedRuntime()::onControl);
        // Clear a player's live subscription when they disconnect, so the loop never polls a gone player.
        PlayerEvent.PLAYER_QUIT.register(player -> sharedRuntime().onDisconnect(player));
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        MineGitCommands.register(dispatcher, sharedRuntime());
    }

    /** The shared command runtime, created once on first use and reused across registrations (#73). */
    static synchronized ServerCommandRuntime sharedRuntime() {
        if (sharedRuntime == null) {
            sharedRuntime = new ServerCommandRuntime();
        }
        return sharedRuntime;
    }
}
