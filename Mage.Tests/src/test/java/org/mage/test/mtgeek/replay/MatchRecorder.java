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

    // "PlayerA puts Atraxa, Grand Unifier from hand onto the Battlefield (source: Sneak Attack)"
    // "PlayerA puts Emrakul from hand onto the Battlefield (source: Show and Tell)"
    // Distinct from P_PLAYS_LAND because the optional (source: X) suffix
    // indicates an effect-driven put (Sneak Attack, Show and Tell, etc.) —
    // not a normal land drop. We capture it as 'put_on_battlefield' so the
    // reducer can add the card with the correct event type for analysis.
    private static final Pattern P_PUT_ON_BATTLEFIELD = Pattern.compile(
            "^(PlayerA|PlayerB) puts (.+?) from hand onto the Battlefield \\(source: (.+?)\\)$"
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

    // "PlayerB's library is shuffled"
    // "PlayerA's library is shuffled (source: Ponder)"
    private static final Pattern P_SHUFFLE = Pattern.compile(
            "^(PlayerA|PlayerB)'s library is shuffled(?: \\(source: (.+?)\\))?$"
    );

    // [HAND|PlayerA] Lightning Bolt|Mountain|Show and Tell
    // [HAND|PlayerB] (empty)
    private static final Pattern P_HAND = Pattern.compile(
            "^\\[HAND\\|(PlayerA|PlayerB)\\] (.+)$"
    );

    // [DRAW|PlayerA] 3 — emitted by MTGeekBasePlayer.drawCards override
    // (XMage writes no native log line for draws).
    private static final Pattern P_DRAW_EXPLICIT = Pattern.compile(
            "^\\[DRAW\\|(PlayerA|PlayerB)\\] (\\d+)$"
    );

    // [MANA|PlayerA] W0 U2 B0 R1 G0 C2 — available mana snapshot from
    // MTGeekBasePlayer.snapshotManaIfChanged (untapped sources heuristic).
    private static final Pattern P_MANA = Pattern.compile(
            "^\\[MANA\\|(PlayerA|PlayerB)\\] W(\\d+) U(\\d+) B(\\d+) R(\\d+) G(\\d+) C(\\d+)$"
    );

    // [LIBVIEW|PlayerA|Ponder] Lightning Bolt|Mountain|Force of Will
    // Player's view of top N library cards (Ponder / Brainstorm / Augur etc.)
    // emitted by MTGeekBasePlayer.snapshotLibraryViewOnce — XMage doesn't write
    // these because they're private to the player.
    private static final Pattern P_LIBVIEW = Pattern.compile(
            "^\\[LIBVIEW\\|(PlayerA|PlayerB)\\|(.+?)\\] (.+)$"
    );

    // "Attacker: Orc Army Token (1/1) unblocked"
    // "Attacker: Raph & Mikey, Troublemakers (7/7) blocked by Nethergoyf (4/5)"
    // P/T in parens reflects CURRENT power/toughness incl. +1/+1 counters.
    private static final Pattern P_COMBAT_ATTACKER = Pattern.compile(
            "^Attacker: (.+?) \\((\\d+)/(\\d+)\\) (?:unblocked|blocked by (.+?) \\((\\d+)/(\\d+)\\))$"
    );

    // "Underground Sea was destroyed by Wasteland"
    // Bowmasters damage / Lightning Bolt / etc. — applies to permanents only.
    private static final Pattern P_DESTROYED = Pattern.compile(
            "^(.+?) was destroyed by (.+?)$"
    );

    // "PlayerA reveals X, Y, Z (source: A)"   — Atraxa ETB, Stock Up, etc.
    // Cards revealed PUBLICLY (different from Ponder's "look at" which stays
    // private). Names are comma-separated, can include commas in card names
    // (e.g. "Raph & Mikey, Troublemakers" — but that's separated by ", " too).
    // We treat the whole pre-"(source: " segment as the card list, then split on ", ".
    private static final Pattern P_REVEALS = Pattern.compile(
            "^(PlayerA|PlayerB) reveals (.+?)(?: \\(source: (.+?)\\))?$"
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

    // "PlayerB puts Murktide Regent from stack onto the Battlefield"
    // Spell RESOLUTION — the moment a permanent actually enters. cast_spell
    // alone is NOT enough: a countered spell goes stack→graveyard instead,
    // so the reducer must wait for this line before adding to battlefield.
    private static final Pattern P_RESOLVE_TO_BF = Pattern.compile(
            "^(PlayerA|PlayerB) puts (.+?) from stack onto the Battlefield$"
    );

    // "PlayerB puts Brainstorm from stack into their graveyard"
    // Instant/sorcery finishing resolution, OR a COUNTERED spell of any type.
    private static final Pattern P_STACK_TO_GY = Pattern.compile(
            "^(PlayerA|PlayerB) puts (.+?) from stack into their graveyard$"
    );

    // "PlayerB puts a card from hand to the top of their library (source: Brainstorm)"
    // Brainstorm put-back direction (hand → library top). Card identity private.
    private static final Pattern P_HAND_TO_LIBRARY = Pattern.compile(
            "^(PlayerA|PlayerB) puts a card from hand to the top of their library(?: \\(source: (.+?)\\))?$"
    );

    // "PlayerA puts Force of Will from library into their hand"
    // NAMED tutor-to-hand (Atraxa ETB pick, Stock Up pick). The anonymous
    // variant ("a card" / "N cards") is P_DRAW — check that FIRST, this规则
    // 的 (.+?) 否则会吞掉 "a card"。
    private static final Pattern P_TUTOR_TO_HAND = Pattern.compile(
            "^(PlayerA|PlayerB) puts (.+?) from library into their hand$"
    );

    // "PlayerB discards Atraxa, Grand Unifier (source: Thoughtseize)"
    private static final Pattern P_DISCARD = Pattern.compile(
            "^(PlayerA|PlayerB) discards (.+?)(?: \\(source: (.+?)\\))?$"
    );

    // "Ability has been fizzled: {T}, Sacrifice {this}: Destroy target nonbasic land."
    // Informational — surfaces in EventLog so spectators understand why an
    // activated ability had no effect (target disappeared).
    private static final Pattern P_FIZZLE = Pattern.compile(
            "^Ability has been fizzled: (.+)$"
    );

    // "PlayerB moves Wasteland from graveyard to the exile zone (source: Murktide Regent)"
    // Delve / escape costs — graveyard count must shrink.
    private static final Pattern P_GY_TO_EXILE = Pattern.compile(
            "^(PlayerA|PlayerB) moves (.+?) from graveyard to the exile zone(?: \\(source: (.+?)\\))?$"
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
        // getTurnStepType() == null means game setup (opening shuffles etc.) —
        // activePlayerId isn't final yet, so synthesizing turn_start there
        // produced a wrong actor (observed: actor=B for T1 when A goes first).
        if (game != null && game.getTurnStepType() != null) {
            int liveTurn = game.getTurnNum();
            if (liveTurn != currentTurn) {
                currentTurn = liveTurn;
                // Synthesize turn_start with the REAL active player — turn
                // parity (odd=A) breaks on extra turns (Emrakul!) and "choose
                // who goes first", so the reducer needs ground truth to untap
                // the correct side.
                String activeName = "";
                if (game.getActivePlayerId() != null) {
                    mage.players.Player ap = game.getPlayer(game.getActivePlayerId());
                    if (ap != null) activeName = ap.getName();
                }
                ReplayEvent ts = new ReplayEvent(counter++, currentTurn, "turn_start");
                ts.actor = resolveActor(activeName);
                events.add(ts);
            }
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

        // --- library view ([LIBVIEW|PlayerA|Ponder] cardA|cardB|cardC) ---
        if (msg.startsWith("[LIBVIEW|")) {
            Matcher mlv = P_LIBVIEW.matcher(msg);
            if (!mlv.matches()) return null;
            ReplayEvent ev = new ReplayEvent(0, 0, "library_view");
            ev.actor = resolveActor(mlv.group(1));
            ev.payload.put("source", mlv.group(2));
            java.util.List<java.util.Map<String, Object>> cards = new java.util.ArrayList<>();
            for (String n : mlv.group(3).split("\\|")) {
                if (n.isEmpty()) continue;
                java.util.Map<String, Object> c = new java.util.LinkedHashMap<>();
                c.put("name", n);
                cards.add(c);
            }
            ev.payload.put("cards", cards);
            return ev;
        }

        // --- hand snapshot ([HAND|PlayerA] cardA|cardB|cardC) ---
        // --- mana snapshot ([MANA|PlayerA] W0 U2 B0 R1 G0 C2) ---
        if (msg.startsWith("[MANA|")) {
            Matcher mm = P_MANA.matcher(msg);
            if (!mm.matches()) return null;
            ReplayEvent ev = new ReplayEvent(0, 0, "mana_snapshot");
            ev.actor = resolveActor(mm.group(1));
            java.util.Map<String, Object> mana = new java.util.LinkedHashMap<>();
            mana.put("W", Integer.parseInt(mm.group(2)));
            mana.put("U", Integer.parseInt(mm.group(3)));
            mana.put("B", Integer.parseInt(mm.group(4)));
            mana.put("R", Integer.parseInt(mm.group(5)));
            mana.put("G", Integer.parseInt(mm.group(6)));
            mana.put("C", Integer.parseInt(mm.group(7)));
            ev.payload.put("mana", mana);
            return ev;
        }

        // --- explicit draw ([DRAW|PlayerA] 3) ---
        if (msg.startsWith("[DRAW|")) {
            Matcher md = P_DRAW_EXPLICIT.matcher(msg);
            if (!md.matches()) return null;
            ReplayEvent ev = new ReplayEvent(0, 0, "draw");
            ev.actor = resolveActor(md.group(1));
            ev.payload.put("n", Integer.parseInt(md.group(2)));
            return ev;
        }

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

        // --- put_on_battlefield (effect-driven, e.g. Sneak Attack, Show and Tell) ---
        // CHECK BEFORE P_PLAYS_LAND because P_PLAYS_LAND would NOT match (the
        // trailing "(source: X)" prevents it) but we want explicit handling here.
        m = P_PUT_ON_BATTLEFIELD.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "put_on_battlefield");
            ev.actor = resolveActor(m.group(1));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", m.group(2));
            ev.payload.put("card", card);
            ev.payload.put("source", m.group(3).trim());
            return ev;
        }

        // --- play_land (no source suffix → real land drop) ---
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

        // --- tutor_to_hand ---  (AFTER P_DRAW: its (.+?) would swallow "a card")
        // "PlayerA puts Force of Will from library into their hand"
        m = P_TUTOR_TO_HAND.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "tutor_to_hand");
            ev.actor = resolveActor(m.group(1));
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("name", m.group(2).trim());
            ev.payload.put("card", card);
            return ev;
        }

        // --- resolve_to_battlefield ---
        // "PlayerB puts Murktide Regent from stack onto the Battlefield"
        m = P_RESOLVE_TO_BF.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "resolve_to_battlefield");
            ev.actor = resolveActor(m.group(1));
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("name", m.group(2).trim());
            ev.payload.put("card", card);
            return ev;
        }

        // --- stack_to_graveyard ---  (instant/sorcery finishing OR countered spell)
        // "PlayerB puts Brainstorm from stack into their graveyard"
        m = P_STACK_TO_GY.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "stack_to_graveyard");
            ev.actor = resolveActor(m.group(1));
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("name", m.group(2).trim());
            ev.payload.put("card", card);
            return ev;
        }

        // --- hand_to_library ---  (Brainstorm put-back)
        // "PlayerB puts a card from hand to the top of their library (source: Brainstorm)"
        m = P_HAND_TO_LIBRARY.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "hand_to_library");
            ev.actor = resolveActor(m.group(1));
            if (m.group(2) != null) ev.payload.put("source", m.group(2).trim());
            return ev;
        }

        // --- discard ---
        // "PlayerB discards Atraxa, Grand Unifier (source: Thoughtseize)"
        m = P_DISCARD.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "discard");
            ev.actor = resolveActor(m.group(1));
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("name", m.group(2).trim());
            ev.payload.put("card", card);
            if (m.group(3) != null) ev.payload.put("source", m.group(3).trim());
            return ev;
        }

        // --- fizzle ---  (informational: activated ability lost its target)
        m = P_FIZZLE.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "fizzle");
            ev.payload.put("ability", m.group(1).trim());
            return ev;
        }

        // --- graveyard_to_exile ---  (delve / escape: Murktide Regent etc.)
        m = P_GY_TO_EXILE.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "graveyard_to_exile");
            ev.actor = resolveActor(m.group(1));
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("name", m.group(2).trim());
            ev.payload.put("card", card);
            if (m.group(3) != null) ev.payload.put("source", m.group(3).trim());
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

        // --- shuffle ---
        // "PlayerA's library is shuffled (source: Ponder)"
        m = P_SHUFFLE.matcher(clean);
        if (m.matches()) {
            // Sourceless shuffles are game-setup deck shuffles (both players,
            // before T1) — noise for spectators. All in-game shuffles (fetch
            // lands, Ponder, Emrakul) carry "(source: X)".
            if (m.group(2) == null) return null;
            ReplayEvent ev = new ReplayEvent(0, 0, "shuffle");
            ev.actor = resolveActor(m.group(1));
            ev.payload.put("source", m.group(2).trim());
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

        // --- destroyed ---
        // "Underground Sea was destroyed by Wasteland"
        m = P_DESTROYED.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "destroyed");
            java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("name", m.group(1).trim());
            ev.payload.put("card", card);
            ev.payload.put("by", m.group(2).trim());
            return ev;
        }

        // --- reveals (Atraxa ETB, Stock Up, etc.) ---
        // "PlayerA reveals X, Y, Z (source: A)"
        m = P_REVEALS.matcher(clean);
        if (m.matches()) {
            ReplayEvent ev = new ReplayEvent(0, 0, "reveal");
            ev.actor = resolveActor(m.group(1));
            String cardsStr = m.group(2).trim();
            // Cards separated by ", "; some card names contain "," (e.g.
            // "Raph & Mikey, Troublemakers"). We split conservatively and
            // accept that compound names may be split — UI shows raw text.
            java.util.List<java.util.Map<String, Object>> cards = new java.util.ArrayList<>();
            for (String n : cardsStr.split(", ")) {
                java.util.Map<String, Object> c = new java.util.LinkedHashMap<>();
                c.put("name", n);
                cards.add(c);
            }
            ev.payload.put("cards", cards);
            if (m.group(3) != null) ev.payload.put("source", m.group(3).trim());
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
