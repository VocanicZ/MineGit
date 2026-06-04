package com.minegit.mod.platform.neoforge;

import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

/** NeoForge implementation of the {@link Platform} {@code @ExpectPlatform} seam. */
public final class PlatformImpl {

    private PlatformImpl() {
    }

    public static String loaderName() {
        return "neoforge";
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
