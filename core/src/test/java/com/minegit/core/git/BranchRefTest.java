package com.minegit.core.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BranchRefTest {

    @Test
    void localFactory_marksRefAsLocal() {
        BranchRef ref = BranchRef.local("master");
        assertEquals("master", ref.getName());
        assertFalse(ref.isRemote());
    }

    @Test
    void remoteFactory_marksRefAsRemoteTracking() {
        BranchRef ref = BranchRef.remote("origin/main");
        assertEquals("origin/main", ref.getName());
        assertTrue(ref.isRemote());
    }

    @Test
    void equalityDistinguishesLocalFromRemoteOfSameName() {
        assertEquals(BranchRef.local("feature"), new BranchRef("feature", false));
        assertNotEquals(BranchRef.local("feature"), BranchRef.remote("feature"));
    }
}
