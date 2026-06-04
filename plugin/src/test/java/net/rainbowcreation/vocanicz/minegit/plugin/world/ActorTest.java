package net.rainbowcreation.vocanicz.minegit.plugin.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** An {@link Actor} is the player behind a command — a name and UUID for commit authorship (Spec B §4). */
class ActorTest {

    @Test
    void fromPlayerCarriesNameAndUuid() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        when(player.getUniqueId()).thenReturn(id);

        Actor actor = Actor.fromPlayer(player);

        assertEquals("Steve", actor.name());
        assertEquals(id, actor.uuid());
    }

    @Test
    void actorsWithSameNameAndUuidAreEqual() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        assertEquals(new Actor("Alex", id), new Actor("Alex", id));
    }
}
