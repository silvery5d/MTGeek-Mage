package org.mage.test.mtgeek.llm;

import mage.cards.Card;
import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.*;

/**
 * B2' T6 — maps a XMage Game + Player into the request Map that matches the
 * JSON contract documented in spec §2.1.
 *
 * The "options" field is NOT included here; it is appended by the caller
 * (e.g. via HookOptions.toRequestList(...)).
 */
public class GameStateSerializer {

    private GameStateSerializer() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Build the top-level request map.
     *
     * @param hook the hook name ("priority", "attack", "block", …)
     * @param me   the player whose perspective we are serialising
     * @param game the live XMage game
     * @return a {@code Map<String,Object>} ready for {@link JsonMini#encode}
     */
    public static Map<String, Object> buildRequest(String hook, Player me, Game game) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("hook", hook);
        req.put("actor", resolveActor(me.getName()));
        req.put("turn", game.getTurnNum());
        req.put("state", buildState(me, game));
        // "options" is appended by the caller
        return req;
    }

    // -----------------------------------------------------------------------
    // Actor name resolution
    // -----------------------------------------------------------------------

    /**
     * Maps XMage internal player names ("PlayerA", "PlayerB") to compact
     * labels ("A", "B") used in the JSON contract.
     */
    static String resolveActor(String playerName) {
        if (playerName == null) return "?";
        if (playerName.contains("PlayerA")) return "A";
        if (playerName.contains("PlayerB")) return "B";
        return "?";
    }

    // -----------------------------------------------------------------------
    // State builders
    // -----------------------------------------------------------------------

    private static Map<String, Object> buildState(Player me, Game game) {
        Map<String, Object> state = new LinkedHashMap<>();

        // Use getTurnStepType() — the same call used by MTGeekSimplePlayer;
        // it returns PhaseStep directly (can be null early in the game).
        PhaseStep stepType = game.getTurnStepType();
        state.put("step", stepType != null ? stepType.toString() : "?");

        UUID activeId = game.getActivePlayerId();
        if (activeId != null) {
            Player activePl = game.getPlayer(activeId);
            state.put("active_player", activePl != null ? resolveActor(activePl.getName()) : "?");
        } else {
            state.put("active_player", "?");
        }

        UUID priorityId = game.getPriorityPlayerId();
        if (priorityId != null) {
            Player priorityPl = game.getPlayer(priorityId);
            state.put("priority_player", priorityPl != null ? resolveActor(priorityPl.getName()) : "?");
        } else {
            state.put("priority_player", "?");
        }

        state.put("players", buildPlayers(game));
        state.put("my_hand", buildHand(me, game));
        state.put("my_battlefield", buildBattlefield(me, game, true));
        state.put("opponent_battlefield", buildBattlefield(me, game, false));
        state.put("stack", buildStack(game));
        state.put("mana_available", buildMana(me, game));
        return state;
    }

    private static Map<String, Object> buildPlayers(Game game) {
        Map<String, Object> ps = new LinkedHashMap<>();
        for (Player p : game.getPlayers().values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("life", p.getLife());
            info.put("library_count", p.getLibrary().size());
            info.put("hand_count", p.getHand().size());
            info.put("graveyard_count", p.getGraveyard().size());
            ps.put(resolveActor(p.getName()), info);
        }
        return ps;
    }

    private static List<Map<String, Object>> buildHand(Player me, Game game) {
        List<Map<String, Object>> hand = new ArrayList<>();
        // me.getHand() returns Cards which extends Set<UUID>
        for (UUID cardId : me.getHand()) {
            Card card = game.getCard(cardId);
            if (card == null) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", card.getName());
            // Use getRules() (no-game overload) for clean oracle text;
            // getRules(game) adds in-game modifications which we don't want here.
            c.put("oracle_text", joinRules(card.getRules()));
            c.put("cmc", (int) card.getManaValue());
            c.put("types", typesOf(card));
            hand.add(c);
        }
        return hand;
    }

    private static List<Map<String, Object>> buildBattlefield(Player me, Game game, boolean mine) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Permanent perm : game.getBattlefield().getAllActivePermanents()) {
            boolean isMine = perm.getControllerId().equals(me.getId());
            if (mine != isMine) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", perm.getName());
            p.put("tapped", perm.isTapped());
            boolean isCreature = perm.isCreature(game);
            p.put("is_creature", isCreature);
            if (isCreature) {
                p.put("power", perm.getPower().getValue());
                p.put("toughness", perm.getToughness().getValue());
            }
            out.add(p);
        }
        return out;
    }

    private static List<String> buildStack(Game game) {
        List<String> stack = new ArrayList<>();
        // SpellStack extends ArrayDeque<StackObject> — for-each works fine
        game.getStack().forEach(s -> stack.add(s.toString()));
        return stack;
    }

    /**
     * Simplified mana counting: tally untapped basic lands controlled by {@code me}.
     * A future iteration can read the real mana pool once XMage exposes it cleanly.
     */
    private static Map<String, Object> buildMana(Player me, Game game) {
        Map<String, Object> mana = new LinkedHashMap<>();
        mana.put("W", 0);
        mana.put("U", 0);
        mana.put("B", 0);
        mana.put("R", 0);
        mana.put("G", 0);
        mana.put("C", 0);

        for (Permanent perm : game.getBattlefield().getAllActivePermanents()) {
            if (!perm.getControllerId().equals(me.getId())) continue;
            if (perm.isTapped()) continue;
            String name = perm.getName();
            switch (name) {
                case "Plains":  mana.put("W", (int) mana.get("W") + 1); break;
                case "Island":  mana.put("U", (int) mana.get("U") + 1); break;
                case "Swamp":   mana.put("B", (int) mana.get("B") + 1); break;
                case "Mountain": mana.put("R", (int) mana.get("R") + 1); break;
                case "Forest":  mana.put("G", (int) mana.get("G") + 1); break;
                case "Wastes":  mana.put("C", (int) mana.get("C") + 1); break;
                default: break;
            }
        }
        return mana;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String joinRules(List<String> rules) {
        if (rules == null || rules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(rules.get(i));
        }
        return sb.toString();
    }

    private static List<String> typesOf(Card card) {
        List<String> out = new ArrayList<>();
        // getCardType() (no-arg) returns base card types, which is what we want
        for (CardType t : card.getCardType()) {
            out.add(t.toString());
        }
        return out;
    }
}
