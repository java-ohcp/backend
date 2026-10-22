package com.ohcp.cropagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Published knowledge snapshot: retain multiple reviewed sources without overwriting canonical advice. */
@Component
public class KnowledgeRepository {
    private final ObjectMapper mapper = new ObjectMapper();
    @Value("${agent.kb.dir}")
    private String kbDir;

    private record Snapshot(Map<String, Map<String, Object>> canonical,
                            Map<String, List<Map<String, Object>>> entries, JsonNode rules, JsonNode sizheng) {}
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), null, null);

    public record EvidenceDocument(String id, String nutrient, List<String> crops, String text, String sourceUrl) {}

    @PostConstruct
    public synchronized void load() {
        Path dir = Path.of(kbDir).toAbsolutePath().normalize();
        try {
            Map<String, Map<String, Object>> canonical = new LinkedHashMap<>();
            Map<String, List<Map<String, Object>>> entries = new LinkedHashMap<>();
            Set<String> ids = new HashSet<>();
            Path file = dir.resolve("deficiency_kb.jsonl");
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> raw = mapper.readValue(line, Map.class);
                    if (raw.containsKey("needsReview") && !Boolean.FALSE.equals(raw.get("needsReview"))) continue;
                    String id = string(raw.get("id")), element = string(raw.get("element"));
                    if (id.isBlank() || element.isBlank()) throw new IllegalArgumentException("Missing knowledge ID/element");
                    if (!ids.add(id)) throw new IllegalArgumentException("Duplicate knowledge ID: " + id);
                    Map<String, Object> entry = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
                    entries.computeIfAbsent(element, ignored -> new ArrayList<>()).add(entry);
                    // First complete plan wins; later crawled sources enrich evidence instead of erasing dosage context.
                    Map<String, Object> previous = canonical.get(element);
                    if (previous == null || (!completePlan(previous) && completePlan(entry))) canonical.put(element, entry);
                }
            }
            Map<String, List<Map<String, Object>>> frozen = new LinkedHashMap<>();
            entries.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
            JsonNode rules = readJson(dir.resolve("symptom_rules.json"));
            JsonNode sizheng = readJson(dir.resolve("sizheng.json"));
            snapshot = new Snapshot(Collections.unmodifiableMap(canonical), Collections.unmodifiableMap(frozen), rules, sizheng);
        } catch (Exception error) {
            // Publish only after the complete replacement has loaded, preserving the old snapshot on failure.
            throw new IllegalStateException("Knowledge reload failed: " + dir, error);
        }
    }

    private JsonNode readJson(Path path) throws Exception { return Files.exists(path) ? mapper.readTree(path.toFile()) : null; }
    public JsonNode rules() { return snapshot.rules(); }
    public JsonNode sizheng() { return snapshot.sizheng(); }
    public int size() { return snapshot.canonical().size(); }
    public List<String> elements() { return List.copyOf(snapshot.canonical().keySet()); }
    public Optional<Map<String, Object>> adviceOf(String element) { return Optional.ofNullable(snapshot.canonical().get(element)); }

    public Optional<Map<String, Object>> adviceOf(String element, String crop) {
        return snapshot.entries().getOrDefault(element, List.of()).stream()
                .filter(entry -> applicable(entry, crop) && completePlan(entry)).findFirst();
    }

    public Optional<Map<String, Object>> adviceOfIgnoreCase(String element) {
        if (element == null) return Optional.empty();
        return snapshot.canonical().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(element))
                .map(Map.Entry::getValue).findFirst();
    }

    public List<EvidenceDocument> evidenceFor(String element, String crop) {
        return snapshot.entries().getOrDefault(element, List.of()).stream()
                .filter(entry -> applicable(entry, crop) && validSource(entry.get("sourceUrl")))
                .map(entry -> new EvidenceDocument(string(entry.get("id")), string(entry.get("elementName")),
                        strings(entry.get("crops")), entryText(entry), string(entry.get("sourceUrl"))))
                .filter(document -> !document.text().isBlank()).toList();
    }

    public boolean appliesTo(String element, String crop) { return !evidenceFor(element, crop).isEmpty(); }
    public boolean hasSource(String element) {
        return snapshot.entries().getOrDefault(element, List.of()).stream().anyMatch(e -> validSource(e.get("sourceUrl")));
    }
    public String evidenceText(String element) {
        return snapshot.entries().getOrDefault(element, List.of()).stream().map(this::entryText)
                .filter(text -> !text.isBlank()).reduce((left, right) -> left + "\n" + right).orElse("");
    }
    public String sourceUrl(String element) { return adviceOf(element).map(e -> string(e.get("sourceUrl"))).orElse(null); }
    public String differential(String element) { return adviceOf(element).map(e -> String.join("；", strings(e.get("differential")))).orElse(null); }
    public String elementName(String element) { return adviceOf(element).map(e -> string(e.get("elementName"))).orElse(element); }

    private String entryText(Map<String, Object> entry) {
        String text = String.join("；", strings(entry.get("colorPattern"))) + "；"
                + String.join("；", strings(entry.get("morphology"))) + "；" + String.join("；", strings(entry.get("symptoms")));
        return (text.replace("；", "").isBlank() ? "" : string(entry.get("elementName")) + " 缺素症：" + text)
                + (entry.get("intro") instanceof String intro ? " " + intro : "");
    }
    private static boolean applicable(Map<String, Object> entry, String crop) {
        List<String> crops = strings(entry.get("crops"));
        return crops.contains("通用") || (crop != null && crops.stream().anyMatch(c -> c.equalsIgnoreCase(crop.strip())));
    }
    private static boolean completePlan(Map<String, Object> entry) {
        return entry.get("intro") instanceof String && entry.get("agronomy") instanceof List<?>
                && entry.get("safety") instanceof List<?> && entry.get("chemicalPlan") instanceof List<?>
                && entry.get("organicPlan") instanceof List<?>;
    }
    private static boolean validSource(Object value) {
        try {
            URI uri = URI.create(string(value));
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException error) { return false; }
    }
    private static String string(Object value) { return value instanceof String text ? text : ""; }
    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
    }
}
