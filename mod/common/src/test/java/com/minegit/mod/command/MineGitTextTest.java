package com.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.git.CommitInfo;
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

    private static int colourOf(Component parent, int siblingIndex) {
        return parent.getSiblings().get(siblingIndex).getStyle().getColor().getValue();
    }
}
