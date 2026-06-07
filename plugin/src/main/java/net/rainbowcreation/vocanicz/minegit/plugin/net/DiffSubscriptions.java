package net.rainbowcreation.vocanicz.minegit.plugin.net;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Diff-overlay subscriber registry: a plain UUID set, no scheduler (snapshot push model). */
public final class DiffSubscriptions {

    private final Set<UUID> subs = ConcurrentHashMap.newKeySet();

    public boolean subscribe(UUID id)    { return subs.add(id); }
    public boolean unsubscribe(UUID id)  { return subs.remove(id); }
    public boolean isSubscribed(UUID id) { return subs.contains(id); }
    public Set<UUID> snapshot()          { return new HashSet<>(subs); }
}
