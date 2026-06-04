package com.minegit.plugin.command;

import com.minegit.core.git.CommitInfo;
import com.minegit.core.model.WorldDiff;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.bukkit.ChatColor;

/**
 * Pure formatting of MineGit chat output (Spec B §5).
 *
 * <p>Every line is built from legacy {@link ChatColor} section codes so the identical string renders
 * on every server from 1.8 to latest; {@link MessageService} owns how the line is delivered
 * (Adventure component when present, legacy {@code sendMessage(String)} otherwise). Keeping the
 * formatting here — free of any {@code CommandSender} — makes it trivially unit-testable.
 */
public final class MineGitFormat {

    /** Deterministic UTC timestamp for commit log lines (no locale/zone surprises in tests). */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private MineGitFormat() {}

    /** The {@code +N/-M/~K} block-delta summary: green added, red removed, yellow changed. */
    public static String summary(int added, int removed, int changed) {
        return ChatColor.GREEN + "+" + added + " "
                + ChatColor.RED + "-" + removed + " "
                + ChatColor.YELLOW + "~" + changed;
    }

    /** {@link #summary(int, int, int)} for a whole-world diff (status, working-vs-HEAD). */
    public static String summary(WorldDiff diff) {
        return summary(diff.getAdded(), diff.getRemoved(), diff.getChanged());
    }

    /** The 7-char abbreviated commit hash (or the whole id if somehow shorter). */
    public static String shortHash(String id) {
        return id.length() >= 7 ? id.substring(0, 7) : id;
    }

    /** A {@code yyyy-MM-dd HH:mm} UTC timestamp from epoch seconds. */
    public static String timestamp(long epochSeconds) {
        return TIMESTAMP.format(Instant.ofEpochSecond(epochSeconds));
    }

    /** One log line: {@code <hash> <author> <message> <time>}, color-coded. */
    public static String commitLine(CommitInfo commit) {
        return ChatColor.YELLOW + shortHash(commit.getId()) + " "
                + ChatColor.GRAY + commit.getAuthor() + " "
                + ChatColor.WHITE + firstLine(commit.getMessage()) + " "
                + ChatColor.DARK_GRAY + timestamp(commit.getEpochSeconds());
    }

    /** The message's first line — commit bodies are rare here, but never wrap a log line. */
    static String firstLine(String message) {
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
