package net.rainbowcreation.vocanicz.minegit.plugin.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * The version-aware messaging seam (Spec B §5). The legacy renderer delivers section-coded strings
 * via {@code sendMessage(String)}, which works on every server 1.8 -> latest; {@link
 * MessageServices#detect()} upgrades to Adventure components only when that API is on the classpath.
 * Adventure is absent from the test classpath, so detection must resolve to the legacy renderer.
 */
class MessageServiceTest {

    @Test
    void legacyRendererSendsRawSectionString() {
        CommandSender sender = mock(CommandSender.class);
        MessageService service = new LegacyMessageService();

        service.send(sender, "hello §aworld");

        verify(sender).sendMessage("hello §aworld");
    }

    @Test
    void detectFallsBackToLegacyWhenAdventureAbsent() {
        assertFalse(MessageServices.isAdventureAvailable());
        assertTrue(MessageServices.detect() instanceof LegacyMessageService);
    }
}
