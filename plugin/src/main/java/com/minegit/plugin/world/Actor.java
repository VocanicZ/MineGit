package com.minegit.plugin.world;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * The player behind a MineGit command: a display name plus a stable {@link UUID}, used as the commit
 * author (Spec B §4). Bukkit-free apart from the {@link #fromPlayer(Player)} factory, so it travels
 * cleanly into core/git code.
 */
public final class Actor {

    private final String name;
    private final UUID uuid;

    public Actor(String name, UUID uuid) {
        this.name = Objects.requireNonNull(name, "name");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
    }

    /** Builds an actor from a live Bukkit {@link Player}. */
    public static Actor fromPlayer(Player player) {
        Objects.requireNonNull(player, "player");
        return new Actor(player.getName(), player.getUniqueId());
    }

    public String name() {
        return name;
    }

    public UUID uuid() {
        return uuid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Actor)) {
            return false;
        }
        Actor that = (Actor) o;
        return name.equals(that.name) && uuid.equals(that.uuid);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + uuid.hashCode();
    }

    @Override
    public String toString() {
        return "Actor(" + name + ", " + uuid + ")";
    }
}
