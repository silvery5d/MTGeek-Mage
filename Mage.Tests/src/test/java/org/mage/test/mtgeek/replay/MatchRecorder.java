package org.mage.test.mtgeek.replay;

import mage.collectors.services.EmptyDataCollector;
import mage.game.Game;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订阅 GameImpl.informPlayers → 解析每行 → ReplayEvent。
 * 解析规则覆盖 4 类：decision / play_land / cast_spell / life_change。
 * 其余行落空（return null），不产生事件。
 */
public class MatchRecorder extends EmptyDataCollector {

    // [Simple|priority] picked: Cast "Lightning Bolt" (score=15.50); runner-up: Pass (score=-1.50); n=4
    private static final Pattern P_DECISION = Pattern.compile(
            "^\\[Simple\\|([^\\]]+)\\] picked: (.+?) \\(score=([-\\d.]+)\\)" +
            "(?:; runner-up: (.+?) \\(score=([-\\d.]+)\\))?; n=(\\d+)$"
    );

    // PlayerA plays Mountain
    private static final Pattern P_PLAYS_LAND = Pattern.compile(
            "^(PlayerA|PlayerB) plays (.+)$"
    );

    // PlayerA casts Lightning Bolt  /  PlayerB casts Serra Angel targeting PlayerA
    private static final Pattern P_CASTS_SPELL = Pattern.compile(
            "^(PlayerA|PlayerB) casts (.+?)(?:\\s+targeting .+)?$"
    );

    // PlayerA loses 3 life  /  PlayerB gains 5 life
    private static final Pattern P_LIFE = Pattern.compile(
            "^(PlayerA|PlayerB) (loses|gains) (\\d+) life$"
    );

    private final List<ReplayEvent> events = new ArrayList<>();
    private int counter = 0;
    private int currentTurn = 0;

    @Override
    public String getServiceCode() {
        return "mtgeek-replay-recorder";
    }

    @Override
    public void onGameStart(Game game) {
        // reset per-game state so the same recorder instance can be reused across games
        events.clear();
        counter = 0;
        currentTurn = 0;
    }

    @Override
    public void onGameLog(Game game, String message) {
        if (message == null || message.isEmpty()) return;
        ReplayEvent ev = parseLogLine(message);
        if (ev != null) {
            ev.i = counter++;
            ev.turn = currentTurn;
            events.add(ev);
        }
    }

    /** Returns an unmodifiable view of all recorded events. */
    public List<ReplayEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * XMage does not embed turn number in informPlayers messages.
     * Callers that track turn boundaries should call this to tag subsequent events.
     */
    public void setTurn(int turn) {
        this.currentTurn = turn;
    }

    // -------------------------------------------------------------------------
    // Internal parsing
    // -------------------------------------------------------------------------

    private ReplayEvent parseLogLine(String msg) {
        Matcher m;

        // --- decision (B1' SimpleAI DecisionLogger format) ---
        if (msg.startsWith("[Simple|")) {
            m = P_DECISION.matcher(msg);
            if (!m.matches()) return null;
            ReplayEvent ev = new ReplayEvent(0, 0, "decision");
            ev.payload.put("hook", m.group(1));
            ev.payload.put("picked", m.group(2));
            ev.payload.put("pickedScore", Double.parseDouble(m.group(3)));
            if (m.group(4) != null) {
                ev.payload.put("runnerUp", m.group(4));
                ev.payload.put("runnerUpScore", Double.parseDouble(m.group(5)));
            }
            ev.payload.put("candidates", Integer.parseInt(m.group(6)));
            return ev;
        }

        // --- play_land ---
        m = P_PLAYS_LAND.matcher(msg);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "play_land");
            ev.actor = resolveActor(m.group(1));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", m.group(2));
            ev.payload.put("card", card);
            return ev;
        }

        // --- cast_spell ---
        m = P_CASTS_SPELL.matcher(msg);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "cast_spell");
            ev.actor = resolveActor(m.group(1));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", m.group(2));
            ev.payload.put("card", card);
            return ev;
        }

        // --- life_change ---
        m = P_LIFE.matcher(msg);
        if (m.matches()) {
            int amount = Integer.parseInt(m.group(3));
            int delta = "loses".equals(m.group(2)) ? -amount : amount;
            ReplayEvent ev = new ReplayEvent(0, 0, "life_change");
            ev.actor = resolveActor(m.group(1));
            ev.payload.put("delta", delta);
            return ev;
        }

        // unknown — fall through, no event
        return null;
    }

    private static String resolveActor(String playerToken) {
        return "PlayerA".equals(playerToken) ? "A" : "B";
    }
}
