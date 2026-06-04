package org.mage.test.mtgeek.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonMiniDecoder {
    private final String s;
    private int p;

    private JsonMiniDecoder(String s) { this.s = s; this.p = 0; }

    static Map<String,Object> parseObject(String json) {
        JsonMiniDecoder d = new JsonMiniDecoder(json);
        d.skipWs();
        Object v = d.parseValue();
        if (!(v instanceof Map)) throw new IllegalArgumentException("JSON root not object: " + json);
        @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) v;
        return m;
    }

    private Object parseValue() {
        skipWs();
        if (p >= s.length()) throw new IllegalArgumentException("eof");
        char c = s.charAt(p);
        if (c == '{') return parseObj();
        if (c == '[') return parseArr();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBool();
        if (c == 'n') { expect("null"); return null; }
        return parseNumber();
    }

    private Map<String,Object> parseObj() {
        expect("{");
        Map<String,Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { p++; return m; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs(); expect(":");
            Object v = parseValue();
            m.put(key, v);
            skipWs();
            if (peek() == ',') { p++; continue; }
            if (peek() == '}') { p++; return m; }
            throw new IllegalArgumentException("obj expected , or } at " + p);
        }
    }

    private List<Object> parseArr() {
        expect("[");
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') { p++; return list; }
        while (true) {
            list.add(parseValue());
            skipWs();
            if (peek() == ',') { p++; continue; }
            if (peek() == ']') { p++; return list; }
            throw new IllegalArgumentException("arr expected , or ] at " + p);
        }
    }

    private String parseString() {
        expect("\"");
        StringBuilder sb = new StringBuilder();
        while (p < s.length()) {
            char c = s.charAt(p++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(p++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '/': sb.append('/'); break;
                    case 'u': {
                        String hex = s.substring(p, p+4); p += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                        break;
                    }
                    default: sb.append(e);
                }
            } else sb.append(c);
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private Boolean parseBool() {
        if (s.startsWith("true", p)) { p += 4; return true; }
        if (s.startsWith("false", p)) { p += 5; return false; }
        throw new IllegalArgumentException("bool at " + p);
    }

    private Object parseNumber() {
        int start = p;
        if (peek() == '-') p++;
        while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.' ||
               s.charAt(p) == 'e' || s.charAt(p) == 'E' || s.charAt(p) == '+' || s.charAt(p) == '-')) p++;
        String n = s.substring(start, p);
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
        return Long.parseLong(n);
    }

    private void expect(String tok) {
        skipWs();
        if (!s.startsWith(tok, p)) throw new IllegalArgumentException("expected " + tok + " at " + p);
        p += tok.length();
    }

    private void skipWs() {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
    }

    private char peek() { skipWs(); return p < s.length() ? s.charAt(p) : '\0'; }
}
