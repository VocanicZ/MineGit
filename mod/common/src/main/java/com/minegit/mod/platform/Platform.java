package com.minegit.mod.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import java.nio.file.Path;

/**
 * The {@code @ExpectPlatform} seam: loader-specific bits live in {@code PlatformImpl} under each of
 * {@code mod:fabric} / {@code mod:neoforge} and are stitched in by architectury-plugin at build
 * time. The scaffold ships two representative seams; feature batches add more as needed.
 */
public final class Platform {

    private Platform() {
    }

    /** Short loader name ({@code "fabric"} / {@code "neoforge"}) for logging and diagnostics. */
    @ExpectPlatform
    public static String loaderName() {
        throw new AssertionError("@ExpectPlatform stub — replaced by PlatformImpl at build time");
    }

    /** The loader's config directory; MineGit will key its level↔repo bindings off the world save. */
    @ExpectPlatform
    public static Path configDir() {
        throw new AssertionError("@ExpectPlatform stub — replaced by PlatformImpl at build time");
    }
}
