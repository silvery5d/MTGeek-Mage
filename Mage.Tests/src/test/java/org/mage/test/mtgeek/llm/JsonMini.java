package org.mage.test.mtgeek.llm;

import java.util.List;
import java.util.Map;

public final class JsonMini {
    private JsonMini() {}

    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        encodeTo(sb, value);
        return sb.toString();
    }

    private static void encodeTo(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Number) { sb.append(v); return; }
        if (v instanceof String) { encodeString(sb, (String) v); return; }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?,?> e : ((Map<?,?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                encodeString(sb, e.getKey().toString());
                sb.append(':');
                encodeTo(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                encodeTo(sb, item);
            }
            sb.append(']');
            return;
        }
        if (v instanceof int[]) {
            sb.append('[');
            int[] arr = (int[]) v;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("JsonMini: unsupported type " + v.getClass());
    }

    private static void encodeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    public static Map<String,Object> decodeObject(String json) {
        return JsonMiniDecoder.parseObject(json);
    }
}
