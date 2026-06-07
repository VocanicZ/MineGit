package net.rainbowcreation.vocanicz.minegit.plugin.net;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiffSubscriptionsTest {

    @Test
    void addAndContains() {
        DiffSubscriptions s = new DiffSubscriptions();
        UUID u = UUID.randomUUID();
        assertTrue(s.subscribe(u));        // true = newly added
        assertTrue(s.isSubscribed(u));
    }

    @Test
    void doubleSubscribeIdempotent() {
        DiffSubscriptions s = new DiffSubscriptions();
        UUID u = UUID.randomUUID();
        s.subscribe(u);
        assertFalse(s.subscribe(u));       // already present
        assertEquals(1, s.snapshot().size());
    }

    @Test
    void unsubscribeRemoves() {
        DiffSubscriptions s = new DiffSubscriptions();
        UUID u = UUID.randomUUID();
        s.subscribe(u);
        assertTrue(s.unsubscribe(u));
        assertFalse(s.isSubscribed(u));
        assertFalse(s.unsubscribe(u));     // idempotent remove
    }

    @Test
    void snapshotIsDefensiveCopy() {
        DiffSubscriptions s = new DiffSubscriptions();
        s.subscribe(UUID.randomUUID());
        Set<UUID> snap = s.snapshot();
        snap.clear();                      // must not affect internal state
        assertEquals(1, s.snapshot().size());
    }
}
