package org.mage.test.mtgeek.llm;

import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.mana.ActivatedManaAbilityImpl;
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
        // Cards.getCards(game) materializes UUID set into real Card instances —
        // direct `for (UUID id : me.getHand())` iteration yields nothing in some
        // game states (notably T1 before main phase fully initializes) even
        // though Cards.size() reports the right count. Use the same idiom
        // MTGeekSimplePlayer uses (see its scoreHandCard loop).
        for (Card card : me.getHand().getCards(game)) {
            if (card == null) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", card.getName());
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
     * Aggregates net mana from every untapped permanent the player controls
     * by walking each permanent's activated mana abilities and summing
     * {@code getNetMana(game)}. Dual lands (e.g. Volcanic Island) contribute
     * to both colors they can produce — slight over-count vs reality, but the
     * LLM uses this as a "what colors do I have access to" signal, not a
     * precise pool. Ancient Tomb's 2 colorless is counted correctly.
     */
    private static Map<String, Object> buildMana(Player me, Game game) {
        int w = 0, u = 0, b = 0, r = 0, g = 0, c = 0;
        for (Permanent perm : game.getBattlefield().getAllActivePermanents()) {
            if (!perm.getControllerId().equals(me.getId())) continue;
            if (perm.isTapped()) continue;
            for (Ability ab : perm.getAbilities()) {
                if (!(ab instanceof ActivatedManaAbilityImpl)) continue;
                ActivatedManaAbilityImpl manaAb = (ActivatedManaAbilityImpl) ab;
                for (Mana net : manaAb.getNetMana(game)) {
                    w += net.getWhite();
                    u += net.getBlue();
                    b += net.getBlack();
                    r += net.getRed();
                    g += net.getGreen();
                    c += net.getColorless();
                }
            }
        }
        Map<String, Object> mana = new LinkedHashMap<>();
        mana.put("W", w);
        mana.put("U", u);
        mana.put("B", b);
        mana.put("R", r);
        mana.put("G", g);
        mana.put("C", c);
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
