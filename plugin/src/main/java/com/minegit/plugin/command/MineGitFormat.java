package com.minegit.plugin.command;

import com.minegit.core.git.CommitInfo;
import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /** Default cap on diff body lines before truncation kicks in (chat would otherwise flood). */
    public static final int DIFF_LINE_CAP = 20;

    /**
     * One colored block-change line: ADD green {@code + x y z id}, REMOVE red {@code - x y z id},
     * CHANGE yellow {@code ~ x y z oldId -> newId}. Coordinates are world-absolute.
     */
    public static String changeLine(BlockChange change) {
        String at = change.getX() + " " + change.getY() + " " + change.getZ();
        switch (change.getKind()) {
            case ADD:
                return ChatColor.GREEN + "+ " + at + " " + idOf(change.getNewState());
            case REMOVE:
                return ChatColor.RED + "- " + at + " " + idOf(change.getOldState());
            case CHANGE:
                return ChatColor.YELLOW + "~ " + at + " "
                        + idOf(change.getOldState()) + " -> " + idOf(change.getNewState());
            default:
                return ChatColor.GRAY + "? " + at;
        }
    }

    /**
     * The chat body for a {@code /mg diff}: one {@link #changeLine} per block change across every
     * dimension (sorted by id) and chunk (already in {@code (cx, cz)} order), capped at {@code cap}
     * lines with a dimmed {@code "…and X more"} footer, and a trailing {@link #summary(WorldDiff)}.
     */
    public static List<String> diffBody(WorldDiff diff, int cap) {
        List<DimensionId> dims = new ArrayList<DimensionId>(diff.getDimensions().keySet());
        dims.sort(Comparator.comparing(DimensionId::getId));

        List<String> lines = new ArrayList<String>();
        int total = 0;
        int hidden = 0;
        for (DimensionId dim : dims) {
            for (ChunkDiff chunkDiff : diff.getChunkDiffs(dim)) {
                for (BlockChange change : chunkDiff.getChanges()) {
                    total++;
                    if (lines.size() < cap) {
                        lines.add(changeLine(change));
                    } else {
                        hidden++;
                    }
                }
            }
        }
        if (hidden > 0) {
            lines.add(ChatColor.DARK_GRAY + "…and " + hidden + " more");
        }
        lines.add(summary(diff));
        return lines;
    }

    /** The block id, or {@code minecraft:air} for an absent (null) state. */
    private static String idOf(BlockState state) {
        return state == null ? BlockState.AIR.getId() : state.getId();
    }
}
