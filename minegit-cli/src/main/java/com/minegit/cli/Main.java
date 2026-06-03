package com.minegit.cli;

import com.minegit.core.api.MineGitVersion;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for the standalone {@code minegit} CLI. With no arguments it prints the version banner;
 * otherwise it dispatches to {@link Cli}, which wires the {@code init}/{@code set}/{@code commit}/
 * {@code log}/{@code status}/{@code diff} subcommands to {@code core} end-to-end (Spec A §10).
 */
public final class Main {

    private Main() {
    }

    /** Human-readable version banner, embedding the core engine release. */
    public static String versionLine() {
        return "minegit " + MineGitVersion.RELEASE;
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println(versionLine());
            return;
        }
        Path cwd = Paths.get("").toAbsolutePath();
        int code = Cli.run(args, cwd, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }
}
