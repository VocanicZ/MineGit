package com.minegit.cli;

import com.minegit.core.api.MineGitVersion;

/**
 * Entry point for the standalone {@code minegit} CLI.
 *
 * <p>Batch-1 scaffold: the {@code init}/{@code set}/{@code commit}/{@code log}/{@code status}/
 * {@code diff} subcommands are wired up in a later issue (Spec A §10). This class proves the
 * {@code application} subproject depends on {@code :core} and runs end-to-end.
 */
public final class Main {

    private Main() {
    }

    /** Human-readable version banner, embedding the core engine release. */
    public static String versionLine() {
        return "minegit " + MineGitVersion.RELEASE;
    }

    public static void main(String[] args) {
        System.out.println(versionLine());
    }
}
