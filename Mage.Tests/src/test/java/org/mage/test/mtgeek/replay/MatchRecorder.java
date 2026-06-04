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

    // [Simple|PlayerA:priority] picked: Cast "Lightning Bolt" (score=15.50); runner-up: Pass (score=-1.50); n=4
    private static final Pattern P_DECISION = Pattern.compile(
            "^\\[Simple\\|([^:]+):([^\\]]+)\\] picked: (.+?) \\(score=([-\\d.]+)\\)" +
            "(?:; runner-up: (.+?) \\(score=([-\\d.]+)\\))?; n=(\\d+)$"
    );

    // PlayerA puts Ancient Tomb from hand onto the Battlefield  (real XMage format, HTML-stripped)
    // NOTE: "puts ... from hand onto the Battlefield" fires for any permanent entering from hand
    // (lands, creatures, artifacts, etc). We still emit event type "play_land" per spec —
    // differentiating card type would require a card-type lookup we don't have in B3.1.
    private static final Pattern P_PLAYS_LAND = Pattern.compile(
            "^(PlayerA|PlayerB) puts (.+?) from hand onto the Battlefield$"
    );

    // PlayerA casts Lightning Bolt  /  PlayerB casts Serra Angel targeting PlayerA
    private static final Pattern P_CASTS_SPELL = Pattern.compile(
            "^(PlayerA|PlayerB) casts (.+?)(?:\\s+targeting .+)?$"
    );

    // PlayerA loses 3 life
    // PlayerA loses 1 life from Polluted Delta
    // PlayerA loses 2 life at combat from Nethergoyf
    // PlayerB gains 5 life
    private static final Pattern P_LIFE = Pattern.compile(
            "^(PlayerA|PlayerB) (loses|gains) (\\d+) life(?:\\s+(?:at combat\\s+)?from .+)?$"
    );

    // PlayerB puts a card from library into their hand
    // PlayerB puts 3 cards from library into their hand
    private static final Pattern P_DRAW = Pattern.compile(
            "^(PlayerA|PlayerB) puts (a card|(\\d+) cards?) from library into their hand$"
    );

    // Turn 5 (or "Turn 5: PlayerA")
    private static final Pattern P_TURN = Pattern.compile(
            "^Turn (\\d+).*"
    );

    private final List<ReplayEvent> events = new ArrayList<>();
    private int counter = 0;
    private int currentTurn = 0;

    // 诊断模式：rawLogPath 非 null 时，把每一条原始 log 行追加到文件（零开销 when null）
    private java.nio.file.Path rawLogPath;

    public void setRawLogPath(java.nio.file.Path p) { this.rawLogPath = p; }

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
        // Track current turn from live Game state (XMage doesn't emit "Turn N" log lines in headless mode)
        if (game != null) {
            currentTurn = game.getTurnNum();
        }
        // 诊断模式：rawLogPath 设置时把原始 log 行追加到文件
        if (rawLogPath != null) {
            try {
                java.nio.file.Files.writeString(rawLogPath, message + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException ignored) {}
        }
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
            String playerName = m.group(1);  // NEW: extract player name
            ev.actor = playerName.contains("PlayerA") ? "A" : (playerName.contains("PlayerB") ? "B" : null);
            ev.payload.put("hook", m.group(2));      // was group(1) before
            ev.payload.put("picked", m.group(3));    // was group(2)
            ev.payload.put("pickedScore", Double.parseDouble(m.group(4)));  // was group(3)
            if (m.group(5) != null) {                // was group(4)
                ev.payload.put("runnerUp", m.group(5));
                ev.payload.put("runnerUpScore", Double.parseDouble(m.group(6)));  // was group(5)
            }
            ev.payload.put("candidates", Integer.parseInt(m.group(7)));  // was group(6)
            return ev;
        }

        // For all XMage-native log lines: strip HTML tags + trailing [hex-suffix] codes
        String clean = msg.replaceAll("<[^>]+>", "")          // strip HTML tags
                          .replaceAll("\\s*\\[[0-9a-f]{3,}\\]", "") // strip trailing [hex-suffix]
                          .trim();

        // --- turn tracking ---
        Matcher mTurn = P_TURN.matcher(clean);
        if (mTurn.matches()) {
            currentTurn = Integer.parseInt(mTurn.group(1));
            return null; // turn-start lines don't emit an event
        }

        // --- play_land ---
        m = P_PLAYS_LAND.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "play_land");
            ev.actor = resolveActor(m.group(1));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", m.group(2));
            ev.payload.put("card", card);
            return ev;
        }

        // --- cast_spell ---
        m = P_CASTS_SPELL.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "cast_spell");
            ev.actor = resolveActor(m.group(1));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", m.group(2));
            ev.payload.put("card", card);
            return ev;
        }

        // --- draw ---
        m = P_DRAW.matcher(clean);
        if (m.matches()) {
            int n = m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
            ReplayEvent ev = new ReplayEvent(0, 0, "draw");
            ev.actor = resolveActor(m.group(1));
            ev.payload.put("n", n);
            return ev;
        }

        // --- life_change ---
        m = P_LIFE.matcher(clean);
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
