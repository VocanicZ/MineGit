package net.rainbowcreation.vocanicz.minegit.mod.overlay.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldLevelAccess;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

/**
 * Fabric implementation of the {@code ClientLevelAccess} {@code @ExpectPlatform} seam (SP2 task C1).
 * Supplies {@code Minecraft.getInstance().level} to the common-side {@link ClientWorldLevelAccess}
 * wrapper — the core-typed read logic lives there because the loader subproject can't see core
 * types on its compile classpath.
 */
@Environment(EnvType.CLIENT)
public final class ClientLevelAccessImpl {

    private ClientLevelAccessImpl() {
    }

    /** A read-only {@link LevelAccess} over the current client level, or {@code null} if none. */
    public static LevelAccess current() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : new ClientWorldLevelAccess(level);
    }
}
