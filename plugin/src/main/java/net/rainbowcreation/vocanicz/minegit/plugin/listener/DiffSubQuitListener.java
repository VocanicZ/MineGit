package net.rainbowcreation.vocanicz.minegit.plugin.listener;

import net.rainbowcreation.vocanicz.minegit.plugin.net.DiffSubscriptions;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drops a quitting player's diff-overlay subscription (Spec §2(e)) so the registry never retains a
 * stale UUID for a disconnected client.
 */
public final class DiffSubQuitListener implements Listener {

    private final DiffSubscriptions subs;

    public DiffSubQuitListener(DiffSubscriptions subs) {
        this.subs = subs;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        subs.unsubscribe(event.getPlayer().getUniqueId());
    }
}
