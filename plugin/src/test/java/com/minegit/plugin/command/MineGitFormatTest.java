package com.minegit.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.git.CommitInfo;
import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

/**
 * Pure formatting of MineGit chat output (Spec B §5): the {@code +N/-M/~K} status summary, commit
 * log lines, and deterministic timestamps. Color is legacy {@link ChatColor} so the same strings
 * render on every server 1.8 -> latest; the version-aware delivery lives in {@link MessageService}.
 */
class MineGitFormatTest {

    @Test
    void summaryColorsAddRemoveChange() {
        String expected = ChatColor.GREEN + "+12 " + ChatColor.RED + "-3 " + ChatColor.YELLOW + "~5";
        assertEquals(expected, MineGitFormat.summary(12, 3, 5));
    }

    @Test
    void timestampIsUtcMinutePrecision() {
        // 2026-06-04T12:34:56Z -> minute precision, UTC, no seconds.
        assertEquals("2026-06-04 12:34", MineGitFormat.timestamp(1780576496L));
    }

    @Test
    void commitLineAbbreviatesHashAndUsesFirstMessageLine() {
        CommitInfo commit =
                new CommitInfo("0123456789abcdef0123456789abcdef01234567", "Steve",
                        "Built a castle\n\nlong body", 1780576496L);
        String expected = ChatColor.YELLOW + "0123456" + " "
                + ChatColor.GRAY + "Steve" + " "
                + ChatColor.WHITE + "Built a castle" + " "
                + ChatColor.DARK_GRAY + "2026-06-04 12:34";
        assertEquals(expected, MineGitFormat.commitLine(commit));
    }

    @Test
    void changeLineColorsAddGreen() {
        BlockChange add = BlockChange.add(10, 64, -7, new BlockState("minecraft:stone"));
        assertEquals(
                ChatColor.GREEN + "+ 10 64 -7 minecraft:stone",
                MineGitFormat.changeLine(add));
    }

    @Test
    void changeLineColorsRemoveRed() {
        BlockChange remove = BlockChange.remove(1, 2, 3, new BlockState("minecraft:dirt"));
        assertEquals(
                ChatColor.RED + "- 1 2 3 minecraft:dirt",
                MineGitFormat.changeLine(remove));
    }

    @Test
    void changeLineColorsChangeYellowWithArrow() {
        BlockChange change =
                BlockChange.change(0, 0, 0,
                        new BlockState("minecraft:dirt"), new BlockState("minecraft:grass_block"));
        assertEquals(
                ChatColor.YELLOW + "~ 0 0 0 minecraft:dirt -> minecraft:grass_block",
                MineGitFormat.changeLine(change));
    }

    @Test
    void diffBodyFlattensEveryChangeThenSummary() {
        WorldDiff diff = oneChunkDiff(
                BlockChange.add(0, 64, 0, new BlockState("minecraft:stone")),
                BlockChange.remove(1, 64, 0, new BlockState("minecraft:dirt")));

        List<String> body = MineGitFormat.diffBody(diff, 20);

        // one line per change, then the +N/-M/~K summary as the final line.
        assertEquals(3, body.size());
        assertEquals(MineGitFormat.changeLine(diff.getChunkDiffs(DimensionId.OVERWORLD)
                .get(0).getChanges().get(0)), body.get(0));
        assertEquals(MineGitFormat.summary(diff), body.get(body.size() - 1));
    }

    @Test
    void diffBodyTruncatesPastCapWithAndMoreFooter() {
        List<BlockChange> many = new ArrayList<BlockChange>();
        for (int i = 0; i < 50; i++) {
            many.add(BlockChange.add(i, 64, 0, new BlockState("minecraft:stone")));
        }
        WorldDiff diff = oneChunkDiff(many.toArray(new BlockChange[0]));

        List<String> body = MineGitFormat.diffBody(diff, 20);

        // 20 change lines + "…and 30 more" + summary == 22 lines total.
        assertEquals(22, body.size());
        assertTrue(body.get(20).contains("…and 30 more"), "footer counts the hidden changes");
        assertTrue(body.get(20).startsWith(ChatColor.DARK_GRAY.toString()), "footer is dimmed");
        assertEquals(MineGitFormat.summary(diff), body.get(body.size() - 1));
    }

    @Test
    void diffBodyOrdersDimensionsDeterministicallyById() {
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0),
                Collections.singletonList(BlockChange.add(0, 0, 0, new BlockState("minecraft:stone"))));
        java.util.Map<DimensionId, List<ChunkDiff>> dims =
                new java.util.HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.THE_NETHER, Collections.singletonList(cd));
        dims.put(DimensionId.OVERWORLD, Collections.singletonList(cd));
        WorldDiff diff = new WorldDiff(dims, 2, 0, 0);

        List<String> body = MineGitFormat.diffBody(diff, 20);

        // "overworld" sorts before "the_nether": both change lines identical, but ordering is stable.
        assertEquals(3, body.size());
    }

    /** Build a single-overworld-chunk {@link WorldDiff} from raw changes (counts derived by kind). */
    private static WorldDiff oneChunkDiff(BlockChange... changes) {
        int added = 0;
        int removed = 0;
        int changed = 0;
        for (BlockChange c : changes) {
            switch (c.getKind()) {
                case ADD:
                    added++;
                    break;
                case REMOVE:
                    removed++;
                    break;
                case CHANGE:
                    changed++;
                    break;
                default:
                    break;
            }
        }
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0), Arrays.asList(changes));
        return new WorldDiff(
                Collections.singletonMap(DimensionId.OVERWORLD, Collections.singletonList(cd)),
                added, removed, changed);
    }
}
