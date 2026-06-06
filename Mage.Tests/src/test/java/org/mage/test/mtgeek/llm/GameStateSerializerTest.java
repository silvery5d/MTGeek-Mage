package org.mage.test.mtgeek.llm;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * B2' T6 — unit tests for {@link GameStateSerializer}.
 *
 * Both tests use the standard CardTestPlayerBase harness: we set up cards,
 * call execute(), then query the paused game state via currentGame + playerA.
 */
public class GameStateSerializerTest extends CardTestPlayerBase {

    // -----------------------------------------------------------------------
    // Test 1: basic shape of the produced request map
    // -----------------------------------------------------------------------

    @Test
    public void buildRequest_basicShape() {
        addCard(Zone.HAND, playerA, "Lightning Bolt");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        Map<String, Object> req = GameStateSerializer.buildRequest("priority", playerA, currentGame);

        // --- top-level keys ---
        assertEquals("priority", req.get("hook"));
        assertEquals("A", req.get("actor"));
        assertNotNull("turn should not be null", req.get("turn"));

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) req.get("state");
        assertNotNull("state should not be null", state);
        assertNotNull("step should not be null", state.get("step"));

        // --- players block ---
        @SuppressWarnings("unchecked")
        Map<String, Object> players = (Map<String, Object>) state.get("players");
        assertNotNull("players should not be null", players);

        @SuppressWarnings("unchecked")
        Map<String, Object> aInfo = (Map<String, Object>) players.get("A");
        assertNotNull("players.A should not be null", aInfo);
        assertEquals(20, aInfo.get("life"));

        // --- my_hand ---
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("my_hand");
        assertNotNull("my_hand should not be null", hand);
        assertEquals("PlayerA should have 1 card in hand", 1, hand.size());
        assertEquals("Lightning Bolt", hand.get(0).get("name"));

        // --- mana_available: 2 untapped Mountains ---
        @SuppressWarnings("unchecked")
        Map<String, Object> mana = (Map<String, Object>) state.get("mana_available");
        assertNotNull("mana_available should not be null", mana);
        assertEquals(2, mana.get("R"));
        assertEquals(0, mana.get("G"));

        // --- opponent_battlefield: Grizzly Bears ---
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> oppBf = (List<Map<String, Object>>) state.get("opponent_battlefield");
        assertNotNull("opponent_battlefield should not be null", oppBf);
        assertEquals("opponent should have 1 permanent", 1, oppBf.size());
        assertEquals("Grizzly Bears", oppBf.get(0).get("name"));
        assertEquals(true, oppBf.get(0).get("is_creature"));

        // --- my_battlefield: 2 Mountains (not creatures) ---
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> myBf = (List<Map<String, Object>>) state.get("my_battlefield");
        assertNotNull("my_battlefield should not be null", myBf);
        assertEquals("PlayerA should have 2 permanents", 2, myBf.size());
        // Mountains are lands, not creatures
        for (Map<String, Object> perm : myBf) {
            assertEquals("Mountain", perm.get("name"));
            assertEquals(false, perm.get("is_creature"));
        }
    }

    // -----------------------------------------------------------------------
    // Test 1c: regression — buildHand must materialize cards via getCards(game)
    // (direct UUID iteration returns nothing in some states, see N=5 bug
    // where 5/5 matches saw "empty hand" on T1 priority)
    // -----------------------------------------------------------------------

    @Test
    public void buildHand_multiCard_returnsAllCards() {
        addCard(Zone.HAND, playerA, "Lightning Bolt");
        addCard(Zone.HAND, playerA, "Brainstorm");
        addCard(Zone.HAND, playerA, "Mountain");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        Map<String, Object> req = GameStateSerializer.buildRequest("priority", playerA, currentGame);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) req.get("state");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("my_hand");
        assertEquals("buildHand must return all 3 cards (not empty)", 3, hand.size());
        // Confirm card_count consistency with hand_count
        @SuppressWarnings("unchecked")
        Map<String, Object> players = (Map<String, Object>) state.get("players");
        @SuppressWarnings("unchecked")
        Map<String, Object> aInfo = (Map<String, Object>) players.get("A");
        assertEquals("hand_count matches my_hand size", 3, aInfo.get("hand_count"));
    }

    // -----------------------------------------------------------------------
    // Test 1b: mana_available recognises dual lands + Ancient Tomb
    // -----------------------------------------------------------------------

    @Test
    public void buildMana_dualLandAndAncientTomb_reportsBothColors() {
        addCard(Zone.BATTLEFIELD, playerA, "Volcanic Island");
        addCard(Zone.BATTLEFIELD, playerA, "Ancient Tomb");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        Map<String, Object> req = GameStateSerializer.buildRequest("priority", playerA, currentGame);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) req.get("state");
        @SuppressWarnings("unchecked")
        Map<String, Object> mana = (Map<String, Object>) state.get("mana_available");

        // Heuristic counts — dual lands / multi-mode abilities may over-count,
        // which is acceptable: LLM uses this as a "what colors are available"
        // signal, not a precise pool. Assert lower bounds only.
        assertTrue("U >= 1 from Volcanic Island", (int) mana.get("U") >= 1);
        assertTrue("R >= 1 from Volcanic Island", (int) mana.get("R") >= 1);
        assertTrue("C >= 2 from Ancient Tomb",    (int) mana.get("C") >= 2);
        // No green / no white expected — Lotus Petal "any color" abilities are
        // not in XMage's getNetMana surface, so they're invisible here. Known limit.
        assertEquals("G should be 0 — no green sources",  0, mana.get("G"));
        assertEquals("W should be 0 — no white sources",  0, mana.get("W"));
    }

    // -----------------------------------------------------------------------
    // Test 2: JSON encodes cleanly (no NPE / serialisation error)
    // -----------------------------------------------------------------------

    @Test
    public void buildRequest_jsonEncodesCleanly() {
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        Map<String, Object> req = GameStateSerializer.buildRequest("priority", playerA, currentGame);
        String json = JsonMini.encode(req);

        assertNotNull("encoded json should not be null", json);
        assertTrue("json should start with {", json.startsWith("{"));
        assertTrue("json should contain the hook", json.contains("\"priority\""));
        assertTrue("json should contain the card name", json.contains("\"Lightning Bolt\""));
        assertTrue("json should contain mana_available key", json.contains("mana_available"));
    }
}
