package org.mage.test.mtgeek.replay;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class MatchRecorderTest {

    @Test
    public void parseLogLine_simpleDecision_emitsDecisionEvent() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "[Simple|priority] picked: Cast \"Lightning Bolt\" (score=15.50); runner-up: Pass (score=-1.50); n=4");
        List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("decision", e.type);
        assertEquals("priority", e.payload.get("hook"));
        assertEquals("Cast \"Lightning Bolt\"", e.payload.get("picked"));
        assertEquals(15.5, ((Number) e.payload.get("pickedScore")).doubleValue(), 1e-6);
        assertEquals("Pass", e.payload.get("runnerUp"));
        assertEquals(4, ((Number) e.payload.get("candidates")).intValue());
    }

    @Test
    public void parseLogLine_playerPlaysLand_emitsPlayLandEvent() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerA plays Mountain");
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
        rec.onGameLog(null, "PlayerA plays Mountain");
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
        rec.onGameLog(null, "PlayerA plays Mountain");
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
}
