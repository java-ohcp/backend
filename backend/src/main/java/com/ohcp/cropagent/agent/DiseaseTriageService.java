package com.ohcp.cropagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohcp.cropagent.api.dto.Dtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

/** Source-backed disease triage before nutrient scoring; never a confirmed disease diagnosis. */
@Component
public class DiseaseTriageService {
    private final JsonNode profiles;

    public DiseaseTriageService(@Value("${agent.kb.dir}") String directory) {
        try {
            Path file = Path.of(directory).resolve("disease_triage.json");
            profiles = Files.exists(file) ? new ObjectMapper().readTree(file.toFile()).path("profiles")
                    : new ObjectMapper().createArrayNode();
            if (!profiles.isArray()) throw new IllegalArgumentException("Disease profiles must be an array");
        } catch (Exception error) { throw new IllegalStateException("Cannot load disease triage knowledge", error); }
    }

    public Optional<Triage> assess(String crop, String text) {
        String symptom = text == null ? "" : text;
        List<DiseaseCandidate> candidates = new ArrayList<>();
        List<SourceRef> sources = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        boolean diseasePattern = false;
        for (JsonNode profile : profiles) {
            Set<String> matched = new LinkedHashSet<>();
            List<String> labels = new ArrayList<>();
            for (JsonNode group : profile.path("groups")) {
                for (JsonNode alias : group.path("aliases")) {
                    if (affirmative(symptom, alias.asText())) {
                        matched.add(group.path("id").asText());
                        labels.add(group.path("label").asText());
                        break;
                    }
                }
            }
            List<String> required = strings(profile.path("required"));
            if (required.size() < 2 || !matched.containsAll(required)) continue;
            diseasePattern = true;
            List<String> crops = strings(profile.path("crops"));
            if (crop == null || crops.stream().noneMatch(c -> c.equalsIgnoreCase(crop.strip()))) continue;
            candidates.add(new DiseaseCandidate(profile.path("id").asText(), profile.path("name").asText(), List.copyOf(labels)));
            sources.add(new SourceRef(profile.path("sourceTitle").asText(), profile.path("sourceUrl").asText()));
            questions.addAll(strings(profile.path("questions")));
            summaries.add(profile.path("summary").asText());
        }
        boolean mold = List.of("白色霉层", "白色霉", "灰色霉层", "灰色霉", "紫灰色霉", "白霉")
                .stream().anyMatch(word -> affirmative(symptom, word));
        boolean lesion = List.of("病斑", "水渍状", "水浸状", "腐烂").stream().anyMatch(word -> affirmative(symptom, word));
        if (!diseasePattern && !(mold && lesion)) return Optional.empty();
        String summary = summaries.isEmpty()
                ? "已发现病斑与霉层等病害样线索。当前作物或特征组合尚无已核对的病害条目，请先进行植保排查。"
                : String.join(" ", summaries);
        if (questions.isEmpty()) questions.add("请确认作物名称，并补充病斑及叶背照片、发生部位和近期温湿条件。");
        return Optional.of(new Triage("DISEASE_SUSPECTED", summary, candidates.stream().limit(3).toList(),
                questions.stream().distinct().limit(4).toList(),
                List.of("先按病害方向核实，不能据此认定缺少某种营养元素。", "记录病斑扩展速度，并请当地植保或农技人员结合实物复核。",
                        "未确认原因前，不按缺素盲目追肥；本结果不提供农药或肥料用量。"),
                sources.stream().distinct().limit(5).toList()));
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private boolean affirmative(String text, String word) {
        if (word.isBlank()) return false;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) {
            String before = text.substring(Math.max(0, at - 12), at).replaceAll("\\s", "");
            if (!before.matches("(?s).*(?:没有|未见|并无|未出现|未发现|没有看到|无|不)(?:明显的?|出现|发生|看到)?$")) return true;
        }
        return false;
    }
}
