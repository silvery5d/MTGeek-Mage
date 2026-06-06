package org.mage.test.mtgeek.llm;

import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.cards.Card;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HookOptions {
    public static class Option {
        public final int i;
        public final String label;
        public final String cardName;

        public Option(int i, String label, String cardName) {
            this.i = i; this.label = label; this.cardName = cardName;
        }

        public Map<String,Object> toMap() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("i", i);
            m.put("label", label);
            if (cardName != null) m.put("card_name", cardName);
            return m;
        }
    }

    public static List<Map<String,Object>> toRequestList(List<Option> options) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Option o : options) out.add(o.toMap());
        return out;
    }

    /**
     * Build the priority-hook option list. Mirrors the enumeration that
     * {@code MTGeekSimplePlayer.priority(Game)} uses:
     * <ul>
     *   <li>index 0 = Pass priority</li>
     *   <li>index 1..N = the N entries of {@code playable}, in order</li>
     * </ul>
     * {@code game} is used to resolve each ability's source card so we can
     * surface a clean {@code card_name} on each option.
     */
    public static List<Option> buildPriorityOptions(List<ActivatedAbility> playable, Game game) {
        List<Option> out = new ArrayList<>();
        out.add(new Option(0, "Pass priority", null));
        if (playable == null) return out;
        for (int i = 0; i < playable.size(); i++) {
            ActivatedAbility ab = playable.get(i);
            String cardName = null;
            mage.constants.Zone zone = null;
            if (game != null && ab.getSourceId() != null) {
                Card src = game.getCard(ab.getSourceId());
                if (src != null) cardName = src.getName();
                zone = game.getState().getZone(ab.getSourceId());
            }
            // Distinguish action type by source zone + ability class — the same
            // card name can produce many distinct options (cast spell, activate
            // mana ability, activate sacrifice ability, etc.) and labelling them
            // all "Cast X" makes them look identical in the LLM prompt and the
            // replay log.
            String verb;
            if (ab instanceof mage.abilities.SpellAbility) {
                verb = "Cast";
            } else if (ab instanceof mage.abilities.mana.ActivatedManaAbilityImpl) {
                verb = "Activate mana of";
            } else if (zone == mage.constants.Zone.BATTLEFIELD) {
                verb = "Activate ability of";
            } else if (zone == mage.constants.Zone.HAND
                    && cardName != null
                    && isLand(game.getCard(ab.getSourceId()))) {
                verb = "Play land";
            } else {
                verb = "Activate";
            }
            String label = cardName != null
                    ? verb + " \"" + cardName + "\""
                    : (ab.toString() == null ? "Activate ability" : ab.toString());
            out.add(new Option(i + 1, label, cardName));
        }
        return out;
    }

    private static boolean isLand(Card c) {
        if (c == null) return false;
        for (mage.constants.CardType t : c.getCardType()) {
            if (t == mage.constants.CardType.LAND) return true;
        }
        return false;
    }

    /**
     * Build option list for {@code selectAttackers}. Each option is one
     * eligible attacker permanent, labelled with name and P/T. The LLM's
     * {@code choices} array is interpreted as a subset of these indices.
     */
    public static List<Option> buildSelectAttackersOptions(List<Permanent> attackers, Game game) {
        List<Option> out = new ArrayList<>();
        if (attackers == null) return out;
        for (int i = 0; i < attackers.size(); i++) {
            Permanent p = attackers.get(i);
            String pt = "(" + p.getPower().getValue() + "/" + p.getToughness().getValue() + ")";
            String label = "Attack with " + p.getName() + " " + pt;
            out.add(new Option(i, label, p.getName()));
        }
        return out;
    }

    /**
     * Build option list for {@code selectBlockers}. Each option is one of
     * <em>my</em> eligible blockers. The labels embed the full attacker
     * index→name mapping so the LLM understands what {@code choices[j]=k}
     * means (block attacker {@code k} with blocker {@code j}, or {@code -1}
     * to leave unblocked).
     */
    public static List<Option> buildSelectBlockersOptions(List<Permanent> blockers, List<Permanent> attackers) {
        List<Option> out = new ArrayList<>();
        if (blockers == null) return out;
        StringBuilder atkLabel = new StringBuilder("attackers: ");
        if (attackers != null) {
            for (int i = 0; i < attackers.size(); i++) {
                if (i > 0) atkLabel.append(", ");
                Permanent a = attackers.get(i);
                atkLabel.append(i).append("=").append(a.getName())
                        .append(" (").append(a.getPower().getValue())
                        .append("/").append(a.getToughness().getValue()).append(")");
            }
        }
        String suffix = "; " + atkLabel + "; -1=no block";
        for (int i = 0; i < blockers.size(); i++) {
            Permanent b = blockers.get(i);
            String label = "Blocker " + i + ": " + b.getName()
                    + " (" + b.getPower().getValue() + "/" + b.getToughness().getValue() + ")"
                    + suffix;
            out.add(new Option(i, label, b.getName()));
        }
        return out;
    }

    /**
     * Build option list for {@code chooseTarget}. Each option is one
     * candidate target (permanent / player / card-in-zone). The LLM's
     * {@code choices} is a subset of indices (one per required target).
     */
    public static List<Option> buildChooseTargetOptions(List<UUID> candidates, Game game) {
        List<Option> out = new ArrayList<>();
        if (candidates == null) return out;
        for (int i = 0; i < candidates.size(); i++) {
            UUID id = candidates.get(i);
            String label = describeTarget(id, game);
            String cardName = cardNameOf(id, game);
            out.add(new Option(i, label, cardName));
        }
        return out;
    }

    /**
     * Build option list for {@code chooseMode}. Index in the returned list
     * matches the index in the {@code List<Mode>} the caller passes in
     * (which itself mirrors {@code Modes.getAvailableModes(...)} order).
     */
    public static List<Option> buildChooseModeOptions(Collection<Mode> modes) {
        List<Option> out = new ArrayList<>();
        if (modes == null) return out;
        int i = 0;
        for (Mode m : modes) {
            String text = m.toString();
            if (text == null || text.isEmpty()) text = "(mode " + i + ")";
            out.add(new Option(i, "Mode " + i + ": " + text, null));
            i++;
        }
        return out;
    }

    /**
     * Build option list for {@code chooseFromHand} (SimplePlayer.choose).
     * Each option is one card from the Cards collection, labelled with name +
     * mana value + type line. Used by Show and Tell, Reanimator picks, etc.
     */
    public static List<Option> buildChooseFromHandOptions(List<Card> cards) {
        List<Option> out = new ArrayList<>();
        if (cards == null) return out;
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            int cmc = (int) c.getManaValue();
            String type = c.getCardType() != null && !c.getCardType().isEmpty()
                    ? c.getCardType().get(0).toString()
                    : "?";
            String label = c.getName() + " (cmc=" + cmc + ", " + type + ")";
            out.add(new Option(i, label, c.getName()));
        }
        return out;
    }

    /**
     * Build option list for {@code chooseUse}. Fixed [No, Yes] pair so
     * {@code choices[0]==1} means "Yes". The {@code prompt} text is woven
     * into each label so the LLM sees the underlying yes/no question.
     */
    public static List<Option> buildChooseUseOptions(String prompt) {
        List<Option> out = new ArrayList<>();
        String safe = (prompt == null) ? "" : prompt;
        out.add(new Option(0, "No (" + safe + ")", null));
        out.add(new Option(1, "Yes (" + safe + ")", null));
        return out;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static String describeTarget(UUID id, Game game) {
        if (id == null) return "null";
        if (game == null) return id.toString().substring(0, 8);
        Permanent p = game.getPermanent(id);
        if (p != null) {
            String pt = p.isCreature(game)
                    ? " (" + p.getPower().getValue() + "/" + p.getToughness().getValue() + ")"
                    : "";
            return p.getName() + pt + " [perm]";
        }
        Player pl = game.getPlayer(id);
        if (pl != null) return pl.getName() + " (life=" + pl.getLife() + ") [player]";
        Card c = game.getCard(id);
        if (c != null) return c.getName() + " [card]";
        return "target-" + id.toString().substring(0, 8);
    }

    private static String cardNameOf(UUID id, Game game) {
        if (id == null || game == null) return null;
        Permanent p = game.getPermanent(id);
        if (p != null) return p.getName();
        Card c = game.getCard(id);
        if (c != null) return c.getName();
        return null;
    }
}
