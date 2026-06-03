package org.mage.test.mtgeek.replay;

import java.util.List;
import java.util.Map;

/** Spectate replay 单事件。type discriminator + 各 type 专属字段，
 *  与 MTGeek/src/types/replay.ts 的 TypeScript union 一一对应。 */
public final class ReplayEvent {
    public int i;
    public int turn;
    public String step;       // 可空（不是所有 type 必有）
    public String type;       // turn_start / phase_change / draw / play_land /
                              // cast_spell / activate / attack / block / damage /
                              // life_change / triggered / decision / game_end
    public String actor;      // "A" / "B" / null
    public Map<String, Object> payload;  // 其余 type 专属字段（card, n, picked, score, ...）

    public ReplayEvent(int i, int turn, String type) {
        this.i = i; this.turn = turn; this.type = type;
        this.payload = new java.util.LinkedHashMap<>();
    }

    /** 输出 JSON 一行。手写不引 Jackson。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"i\":").append(i);
        sb.append(",\"turn\":").append(turn);
        if (step != null) sb.append(",\"step\":").append(jsonStr(step));
        sb.append(",\"type\":").append(jsonStr(type));
        if (actor != null) sb.append(",\"actor\":").append(jsonStr(actor));
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            sb.append(",").append(jsonStr(e.getKey())).append(":").append(jsonValue(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append("\"");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof String) return jsonStr((String) v);
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",");
                sb.append(jsonStr(e.getKey())).append(":").append(jsonValue(e.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            StringBuilder sb = new StringBuilder("[");
            for (int j = 0; j < l.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(jsonValue(l.get(j)));
            }
            sb.append("]");
            return sb.toString();
        }
        return jsonStr(v.toString());
    }
}
