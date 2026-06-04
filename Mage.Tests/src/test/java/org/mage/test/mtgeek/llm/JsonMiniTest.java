package org.mage.test.mtgeek.llm;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class JsonMiniTest {
    @Test
    public void encodeString_escapesNewlinesAndQuotes() {
        assertEquals("\"hi\\n\\\"world\\\"\"", JsonMini.encode("hi\n\"world\""));
    }

    @Test
    public void encodeMap_preservesInsertionOrder() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("a", 1); m.put("b", "x");
        assertEquals("{\"a\":1,\"b\":\"x\"}", JsonMini.encode(m));
    }

    @Test
    public void encodeIntArray() {
        assertEquals("[1,2,3]", JsonMini.encode(new int[]{1,2,3}));
    }

    @Test
    public void encodeNested_objectInList() {
        Map<String,Object> inner = new LinkedHashMap<>();
        inner.put("k", true);
        assertEquals("[{\"k\":true}]", JsonMini.encode(Arrays.asList(inner)));
    }

    @Test
    public void roundtrip_decisionResponseJson() {
        Map<String,Object> obj = JsonMini.decodeObject("{\"choices\":[1,2],\"rationale\":\"hi\"}");
        assertEquals(2, ((List<?>) obj.get("choices")).size());
        assertEquals("hi", obj.get("rationale"));
    }

    @Test
    public void decode_handlesEscapedChars() {
        Map<String,Object> obj = JsonMini.decodeObject("{\"x\":\"a\\nb\"}");
        assertEquals("a\nb", obj.get("x"));
    }

    @Test
    public void encode_null() {
        assertEquals("null", JsonMini.encode(null));
    }

    @Test
    public void encode_boolean() {
        assertEquals("true", JsonMini.encode(Boolean.TRUE));
        assertEquals("false", JsonMini.encode(Boolean.FALSE));
    }

    @Test
    public void decode_emptyObject() {
        Map<String,Object> obj = JsonMini.decodeObject("{}");
        assertTrue(obj.isEmpty());
    }
}
