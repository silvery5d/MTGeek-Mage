package org.mage.test.mtgeek.llm;

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
}
