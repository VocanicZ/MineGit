package com.minegit.mod.command;

import com.minegit.core.git.CommitInfo;
import com.minegit.core.model.WorldDiff;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Pure formatting of MineGit chat output as Brigadier-native text {@link Component}s (Spec D §4 —
 * "no Adventure dependency"). The mod analogue of the plugin's {@code MineGitFormat}, but emitting
 * {@code Component}s coloured with {@link ChatFormatting} rather than legacy section strings.
 *
 * <p>Kept free of any command source so it is unit-testable: tests assert on {@link
 * Component#getString()} and the component styles directly.
 */
public final class MineGitText {

    /** Deterministic UTC timestamp for log lines (no locale/zone surprises in tests). */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /** Default cap on how many commits a single {@code /mg log} prints before truncating. */
    public static final int LOG_LIMIT = 10;

    private MineGitText() {
    }

    /** The {@code +N -M ~K} block-delta summary: green added, red removed, yellow changed. */
    public static Component summary(int added, int removed, int changed) {
        return Component.empty()
                .append(Component.literal("+" + added).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(Component.literal("-" + removed).withStyle(ChatFormatting.RED))
                .append(Component.literal(" "))
                .append(Component.literal("~" + changed).withStyle(ChatFormatting.YELLOW));
    }

    /** {@link #summary(int, int, int)} for a whole-world diff (status, working-vs-HEAD). */
    public static Component summary(WorldDiff diff) {
        return summary(diff.getAdded(), diff.getRemoved(), diff.getChanged());
    }

    /** The 7-char abbreviated commit hash (or the whole id if somehow shorter). */
    public static String shortHash(String id) {
        return id.length() >= 7 ? id.substring(0, 7) : id;
    }

    /** The message's first line — never let a multi-line body wrap a log line. */
    public static String firstLine(String message) {
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    /** A {@code yyyy-MM-dd HH:mm} UTC timestamp from epoch seconds. */
    public static String timestamp(long epochSeconds) {
        return TIMESTAMP.format(Instant.ofEpochSecond(epochSeconds));
    }

    /** One log line: {@code <hash> <author> <message> <time>}, colour-coded. */
    public static Component commitLine(CommitInfo commit) {
        return Component.empty()
                .append(Component.literal(shortHash(commit.getId())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" "))
                .append(Component.literal(commit.getAuthor()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" "))
                .append(Component.literal(firstLine(commit.getMessage())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" "))
                .append(Component.literal(timestamp(commit.getEpochSeconds()))
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** The dimmed {@code "…and X more"} footer when the log is truncated. */
    public static Component andMore(int hidden) {
        return Component.literal("…and " + hidden + " more").withStyle(ChatFormatting.DARK_GRAY);
    }

    /** A success line in MineGit's gold/green house style. */
    public static MutableComponent good(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    /** A neutral/notice line (yellow). */
    public static MutableComponent notice(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }
}
