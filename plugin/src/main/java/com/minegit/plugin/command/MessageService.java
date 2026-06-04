package com.minegit.plugin.command;

import org.bukkit.command.CommandSender;

/**
 * Delivers a pre-formatted (legacy section-coded) line to a {@link CommandSender}. Implementations
 * vary by server capability (Spec B §5): {@link LegacyMessageService} on 1.8-era servers,
 * {@link AdventureMessageService} where the Adventure API is present. The text passed in is always
 * the legacy section-coded form produced by {@link MineGitFormat}; an Adventure-backed renderer
 * up-converts it to a component.
 */
public interface MessageService {

    /** Send one already-formatted line to {@code sender}. */
    void send(CommandSender sender, String legacyText);
}
