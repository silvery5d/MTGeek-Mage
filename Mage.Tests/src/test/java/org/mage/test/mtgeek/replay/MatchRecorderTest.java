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
        // Real XMage format: includes "from hand" zone suffix
        rec.onGameLog(null, "PlayerB casts Lightning Bolt from hand");
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
    public void parseLogLine_castsSpell_html_extractsCleanCardName() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font>PlayerB</font> casts <font>Ponder</font> from hand");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("cast_spell", e.type);
        assertEquals("B", e.actor);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> card = (java.util.Map<String, Object>) e.payload.get("card");
        assertEquals("Ponder", card.get("name"));  // not "Ponder from hand"
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
        rec.onGameLog(null, "PlayerB casts Lightning Bolt from hand");
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

    // --- draw events (Task 6) ---

    @Test
    public void parseLogLine_drawOneCard_emitsDrawWithN1() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font>PlayerB</font> puts a card from library into their hand");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("draw", e.type);
        assertEquals("B", e.actor);
        assertEquals(1, ((Number) e.payload.get("n")).intValue());
    }

    @Test
    public void parseLogLine_drawOneCard_playerA_emitsDrawWithN1() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font color='#20B2AA'>PlayerA</font> puts a card from library into their hand");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("draw", e.type);
        assertEquals("A", e.actor);
        assertEquals(1, ((Number) e.payload.get("n")).intValue());
    }

    @Test
    public void parseLogLine_drawMultipleCards_emitsDrawWithCorrectN() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerB puts 3 cards from library into their hand");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("draw", e.type);
        assertEquals("B", e.actor);
        assertEquals(3, ((Number) e.payload.get("n")).intValue());
    }

    @Test
    public void parseLogLine_drawPlain_noHtml_emitsDrawEvent() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerA puts a card from library into their hand");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("draw", e.type);
        assertEquals("A", e.actor);
        assertEquals(1, ((Number) e.payload.get("n")).intValue());
    }

    // --- NICE events: attack (Task 8) ---

    @Test
    public void parseLogLine_attack_singleCreature_emitsAttackEvent() {
        MatchRecorder rec = new MatchRecorder();
        // Real XMage format (HTML-decorated, from research sample line 89)
        rec.onGameLog(null, "<font color='#20B2AA'>PlayerB</font> attacks <font color='#20B2AA'>PlayerA</font> with 1 creature");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("attack", e.type);
        assertEquals("B", e.actor);
        assertEquals("PlayerA", e.payload.get("target"));
        assertEquals(1, ((Number) e.payload.get("count")).intValue());
    }

    @Test
    public void parseLogLine_attack_multiCreature_emitsAttackEventWithCount() {
        MatchRecorder rec = new MatchRecorder();
        // Real XMage format (HTML-decorated, from research sample line 194)
        rec.onGameLog(null, "<font color='#20B2AA'>PlayerB</font> attacks <font color='#20B2AA'>PlayerA</font> with 2 creatures");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("attack", e.type);
        assertEquals("B", e.actor);
        assertEquals("PlayerA", e.payload.get("target"));
        assertEquals(2, ((Number) e.payload.get("count")).intValue());
    }

    @Test
    public void parseLogLine_attack_playerA_emitsAttackWithActorA() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerA attacks PlayerB with 1 creature");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("attack", e.type);
        assertEquals("A", e.actor);
        assertEquals("PlayerB", e.payload.get("target"));
    }

    // --- NICE events: game_end (Task 8) ---

    @Test
    public void parseLogLine_gameEnd_loser_emitsGameEndWithWinnerInferred() {
        MatchRecorder rec = new MatchRecorder();
        // Real XMage format line 199: "PlayerA has lost the game." (note trailing period)
        rec.onGameLog(null, "<font color='#20B2AA'>PlayerA</font> has lost the game.");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("game_end", e.type);
        // PlayerA lost → winner is B
        assertEquals("B", e.payload.get("winner"));
    }

    @Test
    public void parseLogLine_gameEnd_winner_emitsGameEndWithWinner() {
        MatchRecorder rec = new MatchRecorder();
        // Real XMage format line 200: "PlayerB has won the game" (no trailing period)
        rec.onGameLog(null, "<font color='#20B2AA'>PlayerB</font> has won the game");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("game_end", e.type);
        assertEquals("B", e.payload.get("winner"));
    }

    @Test
    public void parseLogLine_gameEnd_playerA_winner_emitsGameEndWinnerA() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerA has won the game");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("game_end", e.type);
        assertEquals("A", e.payload.get("winner"));
    }

    @Test
    public void parseLogLine_gameEnd_playerB_loser_emitsGameEndWinnerA() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "PlayerB has lost the game.");
        java.util.List<ReplayEvent> events = rec.getEvents();
        assertEquals(1, events.size());
        ReplayEvent e = events.get(0);
        assertEquals("game_end", e.type);
        // PlayerB lost → winner is A
        assertEquals("A", e.payload.get("winner"));
    }

    // --- game_end dedup (B3.2 Task 1) ---

    @Test
    public void gameEnd_emitsOnlyOnce_evenWithBothLossAndWinLines() {
        MatchRecorder rec = new MatchRecorder();
        rec.onGameLog(null, "<font>PlayerB</font> has lost the game");
        rec.onGameLog(null, "<font>PlayerA</font> has won the game");
        java.util.List<ReplayEvent> events = rec.getEvents();
        long gameEnds = events.stream().filter(e -> "game_end".equals(e.type)).count();
        assertEquals("game_end should fire exactly once", 1L, gameEnds);
        // 第一行 lost → winner="A"
        ReplayEvent e = events.stream().filter(ev -> "game_end".equals(ev.type)).findFirst().orElseThrow();
        assertEquals("A", e.payload.get("winner"));
    }

    @Test
    public void gameEnd_winOnly_emitsOnce() {
        MatchRecorder rec = new MatchRecorder();
        // 只有 "won" 行（无 "lost" 先到）
        rec.onGameLog(null, "<font>PlayerA</font> has won the game");
        long gameEnds = rec.getEvents().stream().filter(e -> "game_end".equals(e.type)).count();
        assertEquals(1L, gameEnds);
    }

    // --- turn tracking via game.getTurnNum() (Task 7) ---

    @Test
    public void onGameLog_nullGame_turnStaysAtDefault() {
        MatchRecorder rec = new MatchRecorder();
        // null game → currentTurn stays at 0 (default)
        rec.onGameLog(null, "[Simple|PlayerA:priority] picked: Pass (score=-1.50); n=1");
        assertEquals(1, rec.getEvents().size());
        assertEquals(0, rec.getEvents().get(0).turn);
    }

    @Test
    public void onGameLog_nullGame_setTurnThenLog_turnPreserved() {
        MatchRecorder rec = new MatchRecorder();
        // Simulate external turn injection (e.g., via setTurn) followed by null-game log
        rec.setTurn(5);
        rec.onGameLog(null, "PlayerA puts Mountain from hand onto the Battlefield");
        assertEquals(1, rec.getEvents().size());
        // null game must NOT overwrite the turn we set externally
        assertEquals(5, rec.getEvents().get(0).turn);
    }
}
