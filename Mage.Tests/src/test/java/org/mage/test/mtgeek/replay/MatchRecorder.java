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

    // [LLM|PlayerA:priority] picked: Cast Lightning Bolt; rationale: 打对手 Tarmogoyf 解牌
    private static final Pattern P_LLM_DECISION = Pattern.compile(
            "^\\[LLM\\|([^:]+):([^\\]]+)\\] picked: (.+?); rationale: (.+)$"
    );

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

    // PlayerB casts Ponder from hand  /  PlayerB casts Serra Angel from hand targeting PlayerA
    // Anchored at "from <zone>" to avoid capturing zone suffix as part of the card name.
    // "PlayerA casts Lightning Bolt from hand"
    // "PlayerB casts Thoughtseize targeting PlayerB from hand"
    // "PlayerA casts Surgical Extraction targeting Lotus Petal from hand"
    // Optional " targeting X" sub-clause must NOT be folded into the card name.
    // We separately capture it so the UI can surface targets later.
    private static final Pattern P_CASTS_SPELL = Pattern.compile(
            "^(PlayerA|PlayerB) casts (.+?)(?: targeting (.+?))? from (?:hand|library|graveyard).*$"
    );

    // PlayerA loses 3 life
    // PlayerA loses 1 life from Polluted Delta
    // PlayerA loses 2 life at combat from Nethergoyf
    // PlayerB gains 5 life
    // PlayerA loses 2 life from Ancient Tomb
    // Captures source so UI can label Ancient Tomb / Mana Crypt mana-payment.
    private static final Pattern P_LIFE = Pattern.compile(
            "^(PlayerA|PlayerB) (loses|gains) (\\d+) life(?:\\s+(at combat\\s+)?from (.+))?$"
    );

    // PlayerB puts a card from library into their hand
    // PlayerB puts 3 cards from library into their hand
    private static final Pattern P_DRAW = Pattern.compile(
            "^(PlayerA|PlayerB) puts (a card|(\\d+) cards?) from library into their hand$"
    );

    // PlayerB attacks PlayerA with 1 creature / PlayerB attacks PlayerA with 2 creatures
    // Real XMage format confirmed from research sample lines 89, 113, 148, 167, 194
    private static final Pattern P_ATTACK = Pattern.compile(
            "^(PlayerA|PlayerB) attacks (PlayerA|PlayerB) with (\\d+) creatures?$"
    );

    // PlayerA has lost the game.   (trailing period)
    // PlayerB has won the game     (no trailing period)
    // Real XMage format confirmed from research sample lines 199, 200
    private static final Pattern P_GAME_END = Pattern.compile(
            "^(PlayerA|PlayerB) has (lost|won) the game\\.?$"
    );

    // PlayerB creates a Orc Army Token [230] token
    // PlayerA creates 3 Treasure Tokens
    // Trailing "[hex]" object id may or may not be present; trailing "token(s)"
    // is also optional in some variants.
    private static final Pattern P_TOKEN_CREATE = Pattern.compile(
            "^(PlayerA|PlayerB) creates (?:a|(\\d+)) (.+?) tokens?\\.?$"
    );

    // PlayerB sacrificed Flooded Strand (source: Flooded Strand)
    // After HTML-strip the [hex] tokens are gone; source clause is sometimes absent.
    private static final Pattern P_SACRIFICE = Pattern.compile(
            "^(PlayerA|PlayerB) sacrificed (.+?)(?: \\(source: .+?\\))?$"
    );

    // PlayerB puts Underground Sea from library onto the Battlefield (source: Flooded Strand)
    private static final Pattern P_FETCH_LAND = Pattern.compile(
            "^(PlayerA|PlayerB) puts (.+?) from library onto the Battlefield(?: \\(source: (.+?)\\))?$"
    );

    // PlayerA activates: <ability text> from <source card>
    // PlayerB activates: <ability text> from <source card> targeting <target>
    // Pure tap-mana abilities aren't logged by XMage (they run inline during
    // auto-pay), so only "activated ability" cast events show up here.
    // GREEDY (.+) on ability text so we anchor on the LAST " from " — ability
    // descriptions often contain "from your hand" / "from graveyard" earlier
    // (e.g. Sneak Attack). Non-greedy would split there incorrectly.
    private static final Pattern P_ACTIVATE = Pattern.compile(
            "^(PlayerA|PlayerB) activates: (.+) from (.+?)(?: targeting (.+))?$"
    );

    // PlayerB puts a card from library to the top of their library (source: Ponder)
    // Ponder / Brainstorm "look" effects: the actual card names stay private
    // (XMage uses "look at" not "reveal"), so we only capture the reorder count
    // + source — a signal that N cards were reshuffled.
    private static final Pattern P_LIBRARY_REORDER = Pattern.compile(
            "^(PlayerA|PlayerB) puts a card from library to the top of their library(?: \\(source: (.+?)\\))?$"
    );

    // [HAND|PlayerA] Lightning Bolt|Mountain|Show and Tell
    // [HAND|PlayerB] (empty)
    private static final Pattern P_HAND = Pattern.compile(
            "^\\[HAND\\|(PlayerA|PlayerB)\\] (.+)$"
    );

    // "Attacker: Orc Army Token (1/1) unblocked"
    // "Attacker: Raph & Mikey, Troublemakers (7/7) blocked by Nethergoyf (4/5)"
    // P/T in parens reflects CURRENT power/toughness incl. +1/+1 counters.
    private static final Pattern P_COMBAT_ATTACKER = Pattern.compile(
            "^Attacker: (.+?) \\((\\d+)/(\\d+)\\) (?:unblocked|blocked by (.+?) \\((\\d+)/(\\d+)\\))$"
    );

    /**
     * Fallback 正则——XMage headless 测试模式不会 emit "Turn N" 行（B3.1 研究确认；见
     * docs/research/xmage-log-formats.md）。Production 用 onGameLog 内的
     * game.getTurnNum() 直接拉。本 pattern 保留以备 upstream 改变行为不破。
     * 命中时更新 currentTurn 但不 emit 事件。
     */
    private static final Pattern P_TURN = Pattern.compile(
            "^Turn (\\d+).*"
    );

    // NOTE: block — no samples in research (all attacks were unblocked); skip per task spec.
    // NOTE: damage — folded into life_change ("loses N life at combat from <card>"); no separate type needed.

    private final List<ReplayEvent> events = new ArrayList<>();
    private int counter = 0;
    private int currentTurn = 0;
    private boolean gameEndedEmitted = false;

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
        gameEndedEmitted = false;
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

        // --- hand snapshot ([HAND|PlayerA] cardA|cardB|cardC) ---
        if (msg.startsWith("[HAND|")) {
            Matcher mh = P_HAND.matcher(msg);
            if (!mh.matches()) return null;
            ReplayEvent ev = new ReplayEvent(0, 0, "hand_snapshot");
            ev.actor = resolveActor(mh.group(1));
            String rest = mh.group(2);
            java.util.List<java.util.Map<String, Object>> cards = new java.util.ArrayList<>();
            if (!"(empty)".equals(rest)) {
                for (String name : rest.split("\\|")) {
                    if (name.isEmpty()) continue;
                    java.util.Map<String, Object> c = new java.util.LinkedHashMap<>();
                    c.put("name", name);
                    cards.add(c);
                }
            }
            ev.payload.put("cards", cards);
            return ev;
        }

        // --- LLM decision (B2' DecisionLogger.logLLM format) ---
        if (msg.startsWith("[LLM|")) {
            Matcher mLLM = P_LLM_DECISION.matcher(msg);
            if (!mLLM.matches()) return null;
            ReplayEvent ev = new ReplayEvent(0, 0, "decision");
            String playerName = mLLM.group(1);
            ev.actor = resolveActor(playerName);
            ev.payload.put("hook", mLLM.group(2));
            ev.payload.put("picked", mLLM.group(3));
            ev.payload.put("rationale", mLLM.group(4));
            ev.payload.put("source", "LLM");
            return ev;
        }

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
            if (m.group(3) != null) {
                java.util.List<String> targets = new java.util.ArrayList<>();
                targets.add(m.group(3));
                ev.payload.put("targets", targets);
            }
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
            if (m.group(5) != null) ev.payload.put("source", m.group(5).trim());
            if (m.group(4) != null) ev.payload.put("at_combat", true);
            return ev;
        }

        // --- token_create ---
        // "PlayerB creates a Orc Army Token [230] token"
        // "PlayerA creates 3 Treasure tokens"
        // The HTML-strip earlier already removed the trailing [hex] id, so the
        // pattern works against the post-strip "PlayerB creates a Orc Army Token tokens" form.
        m = P_TOKEN_CREATE.matcher(clean);
        if (m.matches()) {
            int count = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
            String tokenName = m.group(3).trim();
            // Avoid double-noun: log lines say "creates a X Token ... token" so
            // the captured "X Token" already ends with "Token"; strip the
            // trailing duplicate if present.
            ReplayEvent ev = new ReplayEvent(0, 0, "token_create");
            ev.actor = resolveActor(m.group(1));
            java.util.Map<String, Object> tok = new java.util.LinkedHashMap<>();
            tok.put("name", tokenName);
            ev.payload.put("token", tok);
            ev.payload.put("count", count);
            return ev;
        }

        // --- fetch_land ---  (check BEFORE attack so "puts X from library onto..." wins)
        // "PlayerB puts Underground Sea from library onto the Battlefield (source: Flooded Strand)"
        m = P_FETCH_LAND.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "fetch_land");
            ev.actor = resolveActor(m.group(1));
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("name", m.group(2).trim());
            ev.payload.put("card", card);
            if (m.group(3) != null) ev.payload.put("source", m.group(3).trim());
            return ev;
        }

        // --- sacrifice ---  (BEFORE attack)
        // "PlayerB sacrificed Flooded Strand (source: Flooded Strand)"
        m = P_SACRIFICE.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "sacrifice");
            ev.actor = resolveActor(m.group(1));
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("name", m.group(2).trim());
            ev.payload.put("card", card);
            return ev;
        }

        // --- library_reorder ---  (BEFORE activate so we win on the "puts a card from library" prefix)
        // "PlayerB puts a card from library to the top of their library (source: Ponder)"
        m = P_LIBRARY_REORDER.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "library_reorder");
            ev.actor = resolveActor(m.group(1));
            if (m.group(2) != null) ev.payload.put("source", m.group(2).trim());
            return ev;
        }

        // --- activate (non-mana ability) ---
        // "PlayerA activates: <text> from <source>"   (optional "targeting <X>")
        m = P_ACTIVATE.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "activate_ability");
            ev.actor = resolveActor(m.group(1));
            ev.payload.put("ability", m.group(2).trim());
            java.util.Map<String, Object> source = new java.util.LinkedHashMap<>();
            source.put("name", m.group(3).trim());
            ev.payload.put("source", source);
            if (m.group(4) != null) ev.payload.put("target", m.group(4).trim());
            return ev;
        }

        // --- attack ---
        // "PlayerB attacks PlayerA with 1 creature"  /  "with 2 creatures"
        m = P_ATTACK.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "attack");
            ev.actor = resolveActor(m.group(1));
            ev.payload.put("target", m.group(2));
            ev.payload.put("count", Integer.parseInt(m.group(3)));
            return ev;
        }

        // --- combat_attacker (per-attacker detail) ---
        // "Attacker: Orc Army Token (1/1) unblocked"
        // "Attacker: Raph & Mikey, Troublemakers (7/7) blocked by Nethergoyf (4/5)"
        m = P_COMBAT_ATTACKER.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "combat_attacker");
            java.util.Map<String, Object> attacker = new java.util.LinkedHashMap<>();
            attacker.put("name", m.group(1).trim());
            attacker.put("power", Integer.parseInt(m.group(2)));
            attacker.put("toughness", Integer.parseInt(m.group(3)));
            ev.payload.put("attacker", attacker);
            if (m.group(4) != null) {
                java.util.Map<String, Object> blocker = new java.util.LinkedHashMap<>();
                blocker.put("name", m.group(4).trim());
                blocker.put("power", Integer.parseInt(m.group(5)));
                blocker.put("toughness", Integer.parseInt(m.group(6)));
                ev.payload.put("blocker", blocker);
            }
            return ev;
        }

        // --- game_end ---
        // "PlayerA has lost the game."  →  winner is the other player
        // "PlayerB has won the game"    →  winner is that player
        // XMage emits both lines; guard ensures only the first fires.
        m = P_GAME_END.matcher(clean);
        if (m.matches()) {
            if (gameEndedEmitted) return null;  // 守卫：第二条 game_end 行静默丢弃
            gameEndedEmitted = true;
            String player = m.group(1);
            String verb   = m.group(2);  // "lost" or "won"
            ReplayEvent ev = new ReplayEvent(0, 0, "game_end");
            String winner;
            if ("won".equals(verb)) {
                winner = resolveActor(player);
            } else {
                // player lost → the other player won
                winner = "PlayerA".equals(player) ? "B" : "A";
            }
            ev.payload.put("winner", winner);
            ev.payload.put("reason", clean);
            return ev;
        }

        // unknown — fall through, no event
        return null;
    }

    private static String resolveActor(String playerToken) {
        return "PlayerA".equals(playerToken) ? "A" : "B";
    }
}
