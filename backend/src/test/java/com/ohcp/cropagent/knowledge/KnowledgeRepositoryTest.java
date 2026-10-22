package com.ohcp.cropagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeRepositoryTest {
    @TempDir Path directory;
    private Map<String,Object> entry(String id, String crop) {
        var row = new LinkedHashMap<String,Object>();
        row.put("id", id); row.put("element", "N"); row.put("elementName", "氮"); row.put("crops", List.of(crop));
        row.put("sourceUrl", "https://example.org/" + id); row.put("colorPattern", List.of("老叶黄化"));
        row.put("intro", "测试知识"); row.put("agronomy", List.of()); row.put("safety", List.of());
        row.put("organicPlan", List.of()); row.put("chemicalPlan", List.of());
        return row;
    }
    private void write(List<Map<String,Object>> entries) throws Exception {
        var mapper = new ObjectMapper();
        var lines = new ArrayList<String>();
        for (var entry : entries) lines.add(mapper.writeValueAsString(entry));
        Files.write(directory.resolve("deficiency_kb.jsonl"), lines);
    }
    @Test void preservesCanonicalAdviceAndMultipleExactSourcesWhileFilteringCropAndReview() throws Exception {
        var pending = entry("pending", "水稻"); pending.put("needsReview", true);
        write(List.of(entry("canonical", "水稻"), entry("extra", "水稻"), entry("tomato", "番茄"), pending));
        var kb = new KnowledgeRepository(); ReflectionTestUtils.setField(kb, "kbDir", directory.toString()); kb.load();
        assertEquals("canonical", kb.adviceOf("N").orElseThrow().get("id"));
        assertEquals(2, kb.evidenceFor("N", "水稻").size());
        assertEquals("https://example.org/extra", kb.evidenceFor("N", "水稻").get(1).sourceUrl());
        assertFalse(kb.appliesTo("N", "小麦"));
    }
    @Test void failedReloadPreservesLastGoodSnapshot() throws Exception {
        write(List.of(entry("canonical", "水稻")));
        var kb = new KnowledgeRepository(); ReflectionTestUtils.setField(kb, "kbDir", directory.toString()); kb.load();
        Files.writeString(directory.resolve("deficiency_kb.jsonl"), "invalid json");
        assertThrows(IllegalStateException.class, kb::load);
        assertEquals("canonical", kb.adviceOf("N").orElseThrow().get("id"));
    }
}
