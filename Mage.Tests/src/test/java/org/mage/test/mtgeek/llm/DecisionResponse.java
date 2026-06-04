package org.mage.test.mtgeek.llm;

import java.util.List;
import java.util.Map;

public class DecisionResponse {
    public final int[] choices;
    public final String rationale;

    public DecisionResponse(int[] choices, String rationale) {
        this.choices = choices;
        this.rationale = rationale;
    }

    public static DecisionResponse fromJson(String json) {
        Map<String,Object> obj = JsonMini.decodeObject(json);
        Object choicesObj = obj.get("choices");
        Object rationaleObj = obj.get("rationale");
        if (!(choicesObj instanceof List)) throw new IllegalArgumentException("choices not array: " + json);
        if (!(rationaleObj instanceof String)) throw new IllegalArgumentException("rationale not string: " + json);
        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) choicesObj;
        int[] choices = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (!(v instanceof Long)) throw new IllegalArgumentException("choice[" + i + "] not int: " + v);
            choices[i] = ((Long) v).intValue();
        }
        return new DecisionResponse(choices, (String) rationaleObj);
    }
}
