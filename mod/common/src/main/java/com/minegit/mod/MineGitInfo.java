package com.minegit.mod;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Loader-agnostic mod identity. Pure constants with no Minecraft or loader imports, so it links and
 * runs under a plain JUnit classpath — the one seam of the scaffold that is unit-testable without a
 * live server.
 */
public final class MineGitInfo {

    /** The mod id, shared by {@code fabric.mod.json} and {@code neoforge.mods.toml}. */
    public static final String MOD_ID = "minegit";

    /** Human-readable mod name. */
    public static final String MOD_NAME = "MineGit";

    /** Primary command literal plus its aliases ({@code /minegit}, {@code /mg}, {@code /git}). */
    private static final List<String> COMMAND_ALIASES =
            Collections.unmodifiableList(Arrays.asList("minegit", "mg", "git"));

    private MineGitInfo() {
    }

    /** The command literals MineGit registers, primary first. */
    public static List<String> commandAliases() {
        return COMMAND_ALIASES;
    }
}
