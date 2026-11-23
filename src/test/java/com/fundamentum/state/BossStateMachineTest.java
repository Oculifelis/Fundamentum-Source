package com.fundamentum.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BossStateMachineTest {
    enum Phase { SPAWN, FIGHT, ROAR, DYING }

    @Test
    void changesOnlyThroughLegalTransitions() {
        List<String> heard = new ArrayList<>();
        BossStateMachine<Phase> m = new BossStateMachine<>(Phase.class, Phase.SPAWN)
            .allow((from, to) -> from != Phase.DYING)
            .onChange((from, to) -> heard.add(from + ">" + to));
        assertTrue(m.to(Phase.FIGHT));
        assertFalse(m.to(Phase.FIGHT), "already there");
        assertTrue(m.to(Phase.DYING));
        assertFalse(m.to(Phase.FIGHT), "nothing leaves DYING");
        assertEquals(Phase.DYING, m.state());
        assertEquals(List.of("SPAWN>FIGHT", "FIGHT>DYING"), heard);
    }

    @Test
    void clockRestartsOnEveryChange() {
        BossStateMachine<Phase> m = new BossStateMachine<>(Phase.class, Phase.SPAWN);
        m.tick();
        m.tick();
        assertEquals(2, m.ticks());
        m.to(Phase.ROAR);
        assertEquals(0, m.ticks());
        m.tick();
        m.restart();
        assertEquals(0, m.ticks());
        assertEquals(Phase.ROAR, m.state());
    }

    @Test
    void saveAndLoadRoundTripByName() {
        BossStateMachine<Phase> m = new BossStateMachine<>(Phase.class, Phase.SPAWN);
        m.to(Phase.ROAR);
        for (int i = 0; i < 37; i++) {
            m.tick();
        }
        String name = m.name();
        int ticks = m.ticks();
        assertEquals("ROAR", name, "saved as the name, never the ordinal");

        List<String> heard = new ArrayList<>();
        BossStateMachine<Phase> loaded = new BossStateMachine<>(Phase.class, Phase.SPAWN).onChange((f, t) -> heard.add(f + ">" + t));
        loaded.restore(name, ticks);
        assertEquals(Phase.ROAR, loaded.state(), "a save mid-roar reloads mid-roar");
        assertEquals(37, loaded.ticks());
        assertTrue(heard.isEmpty(), "loading is not a transition");
    }

    @Test
    void unknownOrMissingNameStartsAfresh() {
        BossStateMachine<Phase> m = new BossStateMachine<>(Phase.class, Phase.SPAWN);
        m.to(Phase.FIGHT);
        m.restore("PHASE_FROM_A_NEWER_VERSION", 50);
        assertEquals(Phase.SPAWN, m.state());
        assertEquals(0, m.ticks());
        m.restore(null, 9);
        assertEquals(Phase.SPAWN, m.state());
        m.restore("FIGHT", -4);
        assertEquals(0, m.ticks(), "a negative clock is clamped");
    }

    @Test
    void clientFollowsTheSyncedState() {
        int[] wire = {-1};
        BossStateMachine<Phase> server = new BossStateMachine<>(Phase.class, Phase.SPAWN).syncWith(id -> wire[0] = id);
        BossStateMachine<Phase> client = new BossStateMachine<>(Phase.class, Phase.SPAWN);
        assertEquals(Phase.SPAWN.ordinal(), wire[0], "the starting state is synced at once");
        server.to(Phase.ROAR);
        client.tick();
        client.onSynced(wire[0]);
        assertEquals(Phase.ROAR, client.state());
        assertEquals(0, client.ticks(), "a new state restarts the client's clock");
        client.tick();
        client.onSynced(wire[0]);
        assertEquals(1, client.ticks(), "the same state again keeps it");
        server.restore("DYING", 3);
        client.onSynced(wire[0]);
        assertEquals(Phase.DYING, client.state(), "a load is synced too");
        client.onSynced(99);
        assertEquals(Phase.SPAWN, client.state(), "a bad id falls back to the initial state");
    }
}
