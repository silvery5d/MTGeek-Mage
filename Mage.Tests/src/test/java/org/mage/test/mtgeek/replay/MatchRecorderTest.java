package org.mage.test.mtgeek.replay;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class MatchRecorderTest {

    @Test
    public void parseLogLine_simpleDecision_emitsDecisionEvent() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "[Simple|PlayerA:priority] picked: Cast \"Lightning Bolt\" (score=15.50); runner-up: Pass (score=-1.50); n=4");
        List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("decision", e.type);
        assertEquals("A", e.actor);
        assertEquals("priority", e.payload.get("hook"));
        assertEquals("Cast \"Lightning Bolt\"", e.payload.get("picked"));
        assertEquals(15.5, ((Number) e.payload.get("pickedScore")).doubleValue(), 1e-6);
        assertEquals("Pass", e.payload.get("runnerUp"));
        assertEquals(4, ((Number) e.payload.get("candidates")).intValue());
    }

    @Test
    public void parseLogLine_decisionPlayerA_setsActorA() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "[Simple|PlayerA:priority] picked: Cast \"Lightning Bolt\" (score=15.50); runner-up: Pass (score=-1.50); n=4");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("decision", e.type);
        assertEquals("A", e.actor);
        assertEquals("priority", e.payload.get("hook"));
        assertEquals("Cast \"Lightning Bolt\"", e.payload.get("picked"));
        assertEquals(15.5, ((Number) e.payload.get("pickedScore")).doubleValue(), 1e-6);
        assertEquals("Pass", e.payload.get("runnerUp"));
        assertEquals(4, ((Number) e.payload.get("candidates")).intValue());
    }

    @Test
    public void parseLogLine_decisionPlayerB_setsActorB() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "[Simple|PlayerB:selectAttackers] picked: 2 attackers declared (of 2 eligible) (score=20.00); n=1");
        assertEquals(1, rec.getEvents().size());
        assertEquals("B", rec.getEvents().get(0).actor);
        assertEquals("selectAttackers", rec.getEvents().get(0).payload.get("hook"));
    }

    @Test
    public void parseLogLine_playerPlaysLand_emitsPlayLandEvent() {
        MatchRecorder rec = new MatchRecorder();
        // Real XMage format (plain, no HTML)
        rec.onGameLog(null, "PlayerA puts Mountain from hand onto the Battlefield");
        List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("play_land", e.type);
        assertEquals("A", e.actor);
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) e.payload.get("card");
        assertEquals("Mountain", card.get("name"));
    }

    @Test
    public void parseLogLine_playerCastsSpell_emitsCastSpellEvent() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerB casts Lightning Bolt");
        List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("cast_spell", e.type);
        assertEquals("B", e.actor);
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) e.payload.get("card");
        assertEquals("Lightning Bolt", card.get("name"));
    }

    @Test
    public void parseLogLine_playerLosesLife_emitsLifeChangeEvent() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerA loses 3 life");
        List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("life_change", e.type);
        assertEquals("A", e.actor);
        assertEquals(-3, ((Number) e.payload.get("delta")).intValue());
    }

    @Test
    public void parseLogLine_playerGainsLife_emitsLifeChangeEventPositive() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerB gains 5 life");
        List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("life_change", e.type);
        assertEquals("B", e.actor);
        assertEquals(5, ((Number) e.payload.get("delta")).intValue());
    }

    @Test
    public void parseLogLine_unknownLine_returnsNullDoesNothing() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "Some random log line we don't parse");
        assertEquals(0, rec.getEvents().size());
    }

    @Test
    public void multipleEvents_indexesIncrementCorrectly() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerA puts Mountain from hand onto the Battlefield");
        rec.onGameLog(null, "PlayerB casts Lightning Bolt");
        List<ReplayEvent> events = rec.getEvents();
        assertEquals(2, events.size());
        assertEquals(0, events.get(0).i);
        assertEquals(1, events.get(1).i);
    }

    @Test
    public void setTurn_eventsCarryTurnNumber() {
        MatchRecorder rec = new MatchRecorder();
        rec.setTurn(3);
        rec.onGameLog(null, "PlayerA puts Mountain from hand onto the Battlefield");
        List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        assertEquals(3, events.get(0).turn);
    }

    @Test
    public void nullMessage_ignored() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, null);
        assertEquals(0, rec.getEvents().size());
    }

    @Test
    public void emptyMessage_ignored() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "");
        assertEquals(0, rec.getEvents().size());
    }

    @Test
    public void parseLogLine_htmlDecoratedPlayerPlaysLand_stripsAndParses() {
        MatchRecorder rec = new MatchRecorder();
        // Real XMage format: HTML-decorated "puts X from hand onto the Battlefield"
        rec.onGameLog(null, "<font color='red'>PlayerA</font> puts <font object_id='abc'>Mountain</font> [abc] from hand onto the Battlefield");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        assertEquals("play_land", events.get(0).type);
        assertEquals("A", events.get(0).actor);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> card = (java.util.Map<String, Object>) events.get(0).payload.get("card");
        assertEquals("Mountain", card.get("name"));
    }

    @Test
    public void parseLogLine_turnLine_updatesCurrentTurn() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "Turn 5");
        rec.onGameLog(null, "<font>PlayerA</font> puts <font>Mountain</font> from hand onto the Battlefield");
        java.util.List<ReplayEvent> events = rec.getEvents();
        // The play_land event should have turn=5
        ReplayEvent playLand = events.stream()
            .filter(e -> "play_land".equals(e.type))
            .findFirst().orElseThrow();
        assertEquals(5, playLand.turn);
    }

    @Test
    public void parseLogLine_playLand_html_emitsPlayLandEvent() {
        MatchRecorder rec = new MatchRecorder();
        // 真实 XMage 格式（含 HTML 装饰）
        rec.onGameLog(null, "<font color='red'>PlayerA</font> puts <font color='black' object_id='abc'>Ancient Tomb</font> [abc] from hand onto the Battlefield");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("play_land", e.type);
        assertEquals("A", e.actor);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> card = (java.util.Map<String, Object>) e.payload.get("card");
        assertEquals("Ancient Tomb", card.get("name"));
    }

    @Test
    public void parseLogLine_playLand_playerB() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font>PlayerB</font> puts <font>Polluted Delta</font> from hand onto the Battlefield");
        assertEquals(1, rec.getEvents().size());
        assertEquals("B", rec.getEvents().get(0).actor);
    }

    // --- life_change: HTML-decorated variants (Task 5) ---

    @Test
    public void parseLogLine_lifeLossWithSource_emitsLifeChangeNegative() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font color='#20B2AA'>PlayerA</font> loses 1 life from <font color='#B0C4DE' object_id='a5e6d55c-590e-43f3-b1f4-101c1b6fe98c'>Polluted Delta</font> [a5e]");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("life_change", e.type);
        assertEquals("A", e.actor);
        assertEquals(-1, ((Number) e.payload.get("delta")).intValue());
    }

    @Test
    public void parseLogLine_lifeLossAtCombat_emitsLifeChangeNegative() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font color='#20B2AA'>PlayerA</font> loses 2 life at combat from <font color='#696969' object_id='08b6fcfe-ac5a-484a-83f4-2fd7f6f6eb99'>Nethergoyf</font> [08b]");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("life_change", e.type);
        assertEquals("A", e.actor);
        assertEquals(-2, ((Number) e.payload.get("delta")).intValue());
    }

    @Test
    public void parseLogLine_lifeLossPlayerB_emitsLifeChangeNegative() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font color='#20B2AA'>PlayerB</font> loses 2 life from <font color='#696969' object_id='c9cfee50-1264-4ee9-aea4-facda026255a'>Thoughtseize</font> [c9c]");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("life_change", e.type);
        assertEquals("B", e.actor);
        assertEquals(-2, ((Number) e.payload.get("delta")).intValue());
    }

    @Test
    public void parseLogLine_lifeGainWithoutSource_emitsLifeChangePositive() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font>PlayerB</font> gains 3 life");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("life_change", e.type);
        assertEquals("B", e.actor);
        assertEquals(3, ((Number) e.payload.get("delta")).intValue());
    }
}
