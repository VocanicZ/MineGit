package com.minegit.core.api;

/**
 * Version constants for the MineGit engine.
 *
 * <p>{@link #MGRF_FORMAT_VERSION} is the on-disk MineGit Repository Format version written into
 * {@code minegit.json}. {@link #RELEASE} is the human-readable artifact version.
 */
public final class MineGitVersion {

    /** On-disk MGRF / {@code .mgc} format version (see Spec A §5, §6). */
    public static final int MGRF_FORMAT_VERSION = 1;

    /** Human-readable release string. */
    public static final String RELEASE = "0.1.0-SNAPSHOT";

    private MineGitVersion() {
    }
}
