package com.minegit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsObjectWithStringIntAndNestedArray() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("minSection", -4L);
        root.put("sectionCount", 24L);
        List<Object> blocks = new ArrayList<Object>();
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("dim", "overworld");
        b.put("x", 0L);
        b.put("y", 64L);
        b.put("z", -3L);
        b.put("id", "minecraft:stone");
        blocks.add(b);
        root.put("blocks", blocks);

        String text = Json.write(root);
        Object parsed = Json.parse(text);

        assertTrue(parsed instanceof Map, "top level is an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) parsed;
        assertEquals(-4L, out.get("minSection"));
        assertEquals(24L, out.get("sectionCount"));
        @SuppressWarnings("unchecked")
        List<Object> outBlocks = (List<Object>) out.get("blocks");
        assertEquals(1, outBlocks.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> ob = (Map<String, Object>) outBlocks.get(0);
        assertEquals("overworld", ob.get("dim"));
        assertEquals(0L, ob.get("x"));
        assertEquals(-3L, ob.get("z"));
        assertEquals("minecraft:stone", ob.get("id"));
    }

    @Test
    void parsesStringWithEscapedQuoteAndColon() {
        Object parsed = Json.parse("{\"id\":\"a:\\\"b\\\"\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) parsed;
        assertEquals("a:\"b\"", out.get("id"));
    }
}
