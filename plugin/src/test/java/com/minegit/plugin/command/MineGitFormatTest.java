package com.minegit.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minegit.core.git.CommitInfo;
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
}
