package com.ohcp.cropagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 病害知识层：与缺素知识库分开加载，避免病害条目混进元素检索。
 *
 * 定位是"能查到的才回答"——命中靠名称、别名和症状特征词，
 * 没有命中就如实说资料不足，不把病害硬解释成缺素。
 */
@Component
public class DiseaseRepository {

    private static final Logger log = LoggerFactory.getLogger(DiseaseRepository.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> entries = new ArrayList<>();

    @Value("${agent.kb.dir}")
    private String kbDir;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        Path file = Path.of(kbDir).toAbsolutePath().normalize().resolve("disease_kb.jsonl");
        if (!Files.exists(file)) {
            log.info("未找到病害知识库：{}", file);
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    entries.add(mapper.readValue(line, Map.class));
                }
            }
            log.info("病害知识库加载完成：{} 条", entries.size());
        } catch (Exception ex) {
            log.error("病害知识库加载失败：{}", ex.getMessage());
            throw new IllegalStateException("病害知识库加载失败，请检查 " + file, ex);
        }
    }

    public int size() {
        return entries.size();
    }

    public List<Map<String, Object>> all() {
        return Collections.unmodifiableList(entries);
    }

    public Optional<DiseaseMatch> match(String text, String crop) {
        return match(entries, text, crop);
    }

    /**
     * 命中规则：出现病害名称/别名，或至少命中两个症状特征词。
     * 单靠"斑点""发黄"这类泛词不算，避免把缺素问题误判成病害。
     */
    static Optional<DiseaseMatch> match(List<Map<String, Object>> entries, String text, String crop) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        DiseaseMatch best = null;
        for (Map<String, Object> entry : entries) {
            DiseaseMatch current = score(entry, text, crop);
            if (current != null && (best == null || current.score() > best.score())) {
                best = current;
            }
        }
        return Optional.ofNullable(best);
    }

    private static DiseaseMatch score(Map<String, Object> entry, String text, String crop) {
        List<String> crops = strings(entry.get("crops"));
        String cropText = crop == null ? "" : crop.trim();
        if (!cropText.isBlank() && !crops.isEmpty()
                && crops.stream().noneMatch(c -> c.equals(cropText) || c.equals("通用"))) {
            return null;
        }

        String name = String.valueOf(entry.getOrDefault("name", ""));
        int score = 0;
        boolean named = false;
        List<String> matched = new ArrayList<>();

        if (!name.isBlank() && affirmative(text, name)) {
            score += 8;
            named = true;
            matched.add(name);
        }
        for (String alias : strings(entry.get("aliases"))) {
            if (affirmative(text, alias)) {
                score += 5;
                named = true;
                matched.add(alias);
            }
        }

        int symptomHits = 0;
        for (String symptom : strings(entry.get("symptoms"))) {
            if (affirmative(text, symptom)) {
                score += 2;
                symptomHits++;
                matched.add(symptom);
            }
        }
        for (String condition : strings(entry.get("conditions"))) {
            if (text.contains(condition)) {
                score += 1;
            }
        }
        if (!named && symptomHits < 2) {
            return null;
        }
        if (score < 4) {
            return null;
        }
        return new DiseaseMatch(entry, score, matched);
    }

    private static boolean affirmative(String text, String term) {
        if (term.isBlank()) return false;
        for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + term.length())) {
            String before = text.substring(Math.max(0, at - 12), at).replaceAll("\\s", "");
            if (!before.matches("(?s).*(?:没有|未见|并无|未出现|未发现|排除|不是|无|不)(?:明显的?|出现|发生|看到)?$")) return true;
        }
        return false;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    public record DiseaseMatch(Map<String, Object> entry, int score, List<String> matchedFeatures) {
    }
}
