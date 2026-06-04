package com.minegit.mod.platform.fabric;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric implementation of the {@link Platform} {@code @ExpectPlatform} seam. */
public final class PlatformImpl {

    private PlatformImpl() {
    }

    public static String loaderName() {
        return "fabric";
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
