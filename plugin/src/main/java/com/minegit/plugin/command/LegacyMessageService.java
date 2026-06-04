package com.minegit.plugin.command;

import org.bukkit.command.CommandSender;

/**
 * The universal renderer: hands the section-coded string straight to {@code sendMessage(String)},
 * which every Bukkit server from 1.8 to latest interprets natively. Used directly on 1.8-era servers
 * and as the fallback when Adventure is unavailable (Spec B §5).
 */
public final class LegacyMessageService implements MessageService {

    @Override
    public void send(CommandSender sender, String legacyText) {
        sender.sendMessage(legacyText);
    }
}
