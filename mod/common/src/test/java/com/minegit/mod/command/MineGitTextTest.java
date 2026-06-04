package com.minegit.mod.command;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Component formatting of MineGit chat output (issue #60). */
class MineGitTextTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void summaryRendersAddedRemovedChangedDeltas() {
        Component summary = MineGitText.summary(3, 1, 2);
        assertEquals("+3 -1 ~2", summary.getString());
    }

    @Test
    void summaryColoursAddedGreenRemovedRedChangedYellow() {
        Component summary = MineGitText.summary(3, 1, 2);
        // siblings: [+3 GREEN, " ", -1 RED, " ", ~2 YELLOW]
        assertTrue(colourOf(summary, 0) == ChatFormatting.GREEN.getColor());
        assertTrue(colourOf(summary, 2) == ChatFormatting.RED.getColor());
        assertTrue(colourOf(summary, 4) == ChatFormatting.YELLOW.getColor());
    }

    @Test
    void shortHashTakesSevenChars() {
        assertEquals("0123456", MineGitText.shortHash("0123456789abcdef"));
        assertEquals("abc", MineGitText.shortHash("abc"));
    }

    @Test
    void firstLineStopsAtNewline() {
        assertEquals("title", MineGitText.firstLine("title\nbody"));
        assertEquals("solo", MineGitText.firstLine("solo"));
    }

    @Test
    void timestampIsUtcMinutePrecision() {
        assertEquals("2026-06-04 12:00", MineGitText.timestamp(1780574400L));
    }

    @Test
    void commitLineCarriesHashAuthorMessageAndTime() {
        CommitInfo commit =
                new CommitInfo("0123456789abcdef0123456789abcdef01234567", "Steve", "build a wall", 1780574400L);
        String line = MineGitText.commitLine(commit).getString();
        assertEquals("0123456 Steve build a wall 2026-06-04 12:00", line);
    }

    @Test
    void andMoreFooterCountsHiddenCommits() {
        assertEquals("…and 5 more", MineGitText.andMore(5).getString());
    }

    @Test
    void changeLineRendersAddRemoveChangeWithWorldCoordsAndIds() {
        BlockChange add = BlockChange.add(1, 70, 2, new BlockState("minecraft:stone"));
        BlockChange remove = BlockChange.remove(3, 64, 4, new BlockState("minecraft:dirt"));
        BlockChange change = BlockChange.change(
                5, 60, 6, new BlockState("minecraft:dirt"), new BlockState("minecraft:grass_block"));
        assertEquals("+ 1 70 2 minecraft:stone", MineGitText.changeLine(add).getString());
        assertEquals("- 3 64 4 minecraft:dirt", MineGitText.changeLine(remove).getString());
        assertEquals("~ 5 60 6 minecraft:dirt -> minecraft:grass_block",
                MineGitText.changeLine(change).getString());
    }

    @Test
    void changeLineColoursAddGreenRemoveRedChangeYellow() {
        BlockChange add = BlockChange.add(1, 70, 2, new BlockState("minecraft:stone"));
        BlockChange remove = BlockChange.remove(3, 64, 4, new BlockState("minecraft:dirt"));
        BlockChange change = BlockChange.change(
                5, 60, 6, new BlockState("minecraft:dirt"), new BlockState("minecraft:grass_block"));
        assertEquals(ChatFormatting.GREEN.getColor(), wholeColour(MineGitText.changeLine(add)));
        assertEquals(ChatFormatting.RED.getColor(), wholeColour(MineGitText.changeLine(remove)));
        assertEquals(ChatFormatting.YELLOW.getColor(), wholeColour(MineGitText.changeLine(change)));
    }

    @Test
    void diffBodyEmitsOneLinePerChangeThenASummary() {
        WorldDiff diff = oneDimensionDiff(
                BlockChange.add(1, 70, 2, new BlockState("minecraft:stone")),
                BlockChange.remove(3, 64, 4, new BlockState("minecraft:dirt")));
        List<Component> body = MineGitText.diffBody(diff, MineGitText.DIFF_LINE_CAP);
        assertEquals(3, body.size());
        assertEquals("+ 1 70 2 minecraft:stone", body.get(0).getString());
        assertEquals("- 3 64 4 minecraft:dirt", body.get(1).getString());
        assertEquals("+1 -1 ~0", body.get(2).getString());
    }

    @Test
    void diffBodyTruncatesPastTheCapWithAnAndMoreFooter() {
        List<BlockChange> changes = new ArrayList<BlockChange>();
        for (int i = 0; i < 5; i++) {
            changes.add(BlockChange.add(i, 70, 0, new BlockState("minecraft:stone")));
        }
        WorldDiff diff = oneDimensionDiff(changes.toArray(new BlockChange[0]));
        List<Component> body = MineGitText.diffBody(diff, 2);
        // 2 change lines + "…and 3 more" footer + summary
        assertEquals(4, body.size());
        assertEquals("…and 3 more", body.get(2).getString());
        assertEquals("+5 -0 ~0", body.get(3).getString());
    }

    private static WorldDiff oneDimensionDiff(BlockChange... changes) {
        int added = 0;
        int removed = 0;
        int changed = 0;
        for (BlockChange c : changes) {
            switch (c.getKind()) {
                case ADD: added++; break;
                case REMOVE: removed++; break;
                case CHANGE: changed++; break;
                default: break;
            }
        }
        ChunkDiff chunkDiff = new ChunkDiff(new ChunkPos(0, 0), Arrays.asList(changes));
        Map<DimensionId, List<ChunkDiff>> dims = new HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(new DimensionId("overworld"), Collections.singletonList(chunkDiff));
        return new WorldDiff(dims, added, removed, changed);
    }

    private static int colourOf(Component parent, int siblingIndex) {
        return parent.getSiblings().get(siblingIndex).getStyle().getColor().getValue();
    }

    private static int wholeColour(Component component) {
        return component.getStyle().getColor().getValue();
    }
}
