package net.rainbowcreation.vocanicz.minegit.plugin.listener;

import net.rainbowcreation.vocanicz.minegit.plugin.command.MineGitCommand;
import net.rainbowcreation.vocanicz.minegit.plugin.net.DiffSubDecision;
import net.rainbowcreation.vocanicz.minegit.plugin.net.DiffSubscriptions;
import net.rainbowcreation.vocanicz.minegit.protocol.Protocol;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Incoming {@code minegit:diffsub} control listener (Spec §2(d)): decodes a SUBSCRIBE/UNSUBSCRIBE
 * payload via the pure {@link DiffSubDecision}, gates SUBSCRIBE on {@link MineGitCommand#PERM_USE},
 * and on success registers the player and pushes the current working-vs-HEAD overlay. UNSUBSCRIBE is
 * ungated. Malformed/unpermitted payloads are silently dropped.
 */
public final class DiffSubListener implements PluginMessageListener {

    private final DiffSubscriptions subs;
    private final MineGitCommand command;

    public DiffSubListener(DiffSubscriptions subs, MineGitCommand command) {
        this.subs = subs;
        this.command = command;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Protocol.DIFF_CONTROL_CHANNEL.equals(channel)) {
            return;
        }
        switch (DiffSubDecision.decide(message, () -> player.hasPermission(MineGitCommand.PERM_USE))) {
            case SUBSCRIBE_PUSH:
                subs.subscribe(player.getUniqueId());
                command.pushCurrentDiff(player);
                break;
            case UNSUBSCRIBE:
                subs.unsubscribe(player.getUniqueId());
                break;
            case IGNORE:
            case DROP:
            default:
                break;
        }
    }
}
