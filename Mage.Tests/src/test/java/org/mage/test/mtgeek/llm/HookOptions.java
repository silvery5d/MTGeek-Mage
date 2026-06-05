package org.mage.test.mtgeek.llm;

import mage.abilities.ActivatedAbility;
import mage.cards.Card;
import mage.game.Game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            if (game != null && ab.getSourceId() != null) {
                Card src = game.getCard(ab.getSourceId());
                if (src != null) cardName = src.getName();
            }
            String label = cardName != null
                    ? "Cast \"" + cardName + "\""
                    : (ab.toString() == null ? "Activate ability" : ab.toString());
            out.add(new Option(i + 1, label, cardName));
        }
        return out;
    }
}
