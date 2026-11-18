package com.ohcp.cropagent.ontology;

import com.ohcp.cropagent.knowledge.EvidenceSearchService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Service
public class OntologyQuestionService {
    private static final String NS = "https://ohcp.example/ontology#";
    private final OntologyReasoningService reasoning;
    private final EvidenceSearchService evidence;
    private final Path root;
    public OntologyQuestionService(OntologyReasoningService reasoning, EvidenceSearchService evidence,
                                   @Value("${agent.ontology.dir:../data/ontology}") String directory) {
        this.reasoning = reasoning; this.evidence = evidence; root = Path.of(directory);
    }
    public record Question(@NotBlank @Size(max=80) String crop, @NotBlank @Size(min=4,max=500) String question) {}
    public record Slots(String crop, String cropId, String stage, List<OntologyReasoningService.ObservedSymptom> observations, List<String> missing) {}
    public record Stage(String id, String title, String status, String summary) {}
    public record Answer(String status, String question, Slots slots, List<Stage> stages,
                         OntologyReasoningService.Result reasoning, List<EvidenceSearchService.Match> evidence,
                         String answer, String mode) {}

    public Answer ask(Question request) throws IOException {
        List<Stage> stages = new ArrayList<>();
        Model model = RDFDataMgr.loadModel(root.resolve("ohcp-ontology.ttl").toString());
        try {
            String cropId = null, cropLabel = request.crop().trim();
            List<Resource> knownCrops = model.listSubjectsWithProperty(RDF.type, model.createResource(NS+"Crop")).toList();
            for (Resource crop : knownCrops) {
                String label = label(model, crop);
                if (request.crop().trim().equals(label) || request.crop().trim().equals(crop.getLocalName())) {
                    cropId = crop.getLocalName(); cropLabel = label; break;
                }
            }
            List<String> missing = new ArrayList<>();
            List<OntologyReasoningService.ObservedSymptom> observations = parse(request.question(), missing);
            for (Resource known : knownCrops) {
                if (request.question().contains(label(model, known)) && !known.getLocalName().equals(cropId))
                    missing.add("作物名称与描述中提及的作物不一致，请核对");
            }
            String stage = List.of("分蘖期", "苗期", "拔节期", "开花期", "结果期", "灌浆期").stream()
                    .filter(request.question()::contains).findFirst().orElse("");
            Slots slots = new Slots(cropLabel, cropId, stage, observations, List.copyOf(missing));
            stages.add(new Stage("parse", "解析观察", "DONE", "按明确的部位、形态词提取；未识别内容不猜测。"));
            if (cropId == null) return stopped("NOT_COVERED", request, slots, stages, "知识库未收录该作物，请核对作物名称。当前本体收录：" + knownCrops.stream().map(c -> label(model,c)).sorted().collect(java.util.stream.Collectors.joining("、")) + "。");
            if (!missing.isEmpty() || observations.isEmpty()) return stopped("NEED_MORE_INFO", request, slots, stages, "请补充或澄清：" + String.join("；", missing));
            var result = reasoning.analyze(new OntologyReasoningService.Request(cropId, observations));
            stages.add(new Stage("query", "查询本体", result.query()==null ? "SKIPPED" : "DONE", "从 TTL 读取候选集合，实际查询与结果见下方。"));
            stages.add(new Stage("reason", "执行规则", "DONE", "由外置规则推导候选、排除及冲突，返回逐条依据。"));
            List<EvidenceSearchService.Match> matches = List.of();
            String evidenceStatus = "SKIPPED", evidenceSummary = "本次没有可供补证的候选，不执行证据检索。";
            if (result.status().equals("CANDIDATES")) {
                Set<String> supportedLabels = new HashSet<>();
                result.candidates().stream().filter(c -> c.decision().equals("SUPPORTED"))
                        .forEach(c -> supportedLabels.add(label(model, model.createResource(c.nutrient()))));
                try {
                    matches = evidence.search(cropLabel, request.question(), 20).stream()
                            .filter(match -> supportedLabels.contains(match.nutrient())).limit(5).toList();
                    evidenceStatus = matches.isEmpty() ? "EMPTY" : "DONE";
                    evidenceSummary = matches.isEmpty() ? "未检索到适用证据，不生成施肥细节。" : "仅保留与受支持元素、当前作物适用范围一致的证据。";
                } catch (RuntimeException unavailable) {
                    evidenceStatus = "UNAVAILABLE"; evidenceSummary = "证据服务暂不可用，本体结果保留，不补写施肥细节。";
                }
            }
            stages.add(new Stage("evidence", "检索证据", evidenceStatus, evidenceSummary));
            String answer;
            if (result.status().equals("CONFLICT")) answer = "观察或本体存在冲突，暂不提供候选判断。" + result.conflicts().stream().map(OntologyReasoningService.Conflict::reason).distinct().collect(java.util.stream.Collectors.joining("；"));
            else if (!result.status().equals("CANDIDATES")) answer = "当前观察未形成受支持的候选，请核对部位、形态与先后顺序。";
            else {
                String names = result.candidates().stream().filter(c -> c.decision().equals("SUPPORTED"))
                        .map(OntologyReasoningService.Candidate::label).collect(java.util.stream.Collectors.joining("、"));
                answer = "通用本体规则支持复核的候选为："+names+"。这不是该作物的确诊，作物适用关系尚待人工审核。";
                if (matches.isEmpty()) answer += "当前无可用补充证据，不提供施肥用量或具体方案。";
                else answer += "下方列出检索到的原始证据，请核对来源；具体管理需结合田间检测及当地农技指导。";
            }
            stages.add(new Stage("answer", "组织输出", "DONE", "采用确定性模板，只复述结构化结果；未调用大模型，不新增事实。"));
            return new Answer(result.status(), request.question(), slots, List.copyOf(stages), result, matches, answer, "TEMPLATE");
        } finally { model.close(); }
    }

    private static List<OntologyReasoningService.ObservedSymptom> parse(String text, List<String> missing) {
        List<OntologyReasoningService.ObservedSymptom> observations = new ArrayList<>();
        if (text.matches("(?s).*(没有|不是|并非|未见|不).{0,6}(黄|绿|坏死|老叶|新叶).*")) {
            missing.add("存在否定描述，请明确实际观察到的部位与形态"); return observations;
        }
        for (String clause : text.split("[，,；;。\\n]+")) {
            List<String> parts = new ArrayList<>();
            if (clause.matches(".*(老叶|下部叶|下部叶片).*")) parts.add("OldLeaf");
            if (clause.matches(".*(新叶|嫩叶|顶部叶).*")) parts.add("YoungLeaf");
            if (clause.contains("叶缘")) parts.add("LeafMargin");
            List<String> patterns = new ArrayList<>();
            if (clause.matches(".*(均匀黄化|均匀发黄|整叶发黄|整体发黄).*")) patterns.add("UniformChlorosis");
            if (clause.matches(".*(脉间失绿|脉间发黄).*")
                    || (clause.contains("黄") && clause.matches(".*(叶脉仍绿|叶脉还是绿).*"))) patterns.add("InterveinalChlorosis");
            if (clause.contains("叶缘") && clause.matches(".*(坏死|焦枯).*")) patterns.add("MarginNecrosis");
            if (parts.size() > 1 && !patterns.isEmpty()) { missing.add("同一句有多个部位，请分句说明各自的形态"); continue; }
            if (!parts.isEmpty() && patterns.isEmpty()) missing.add("“" + clause.strip() + "”的形态不明确，请说明均匀黄化、脉间失绿或叶缘坏死");
            if (parts.size()==1) for (String pattern : patterns) observations.add(new OntologyReasoningService.ObservedSymptom(parts.get(0), pattern,
                    clause.matches(".*(先|最早|开始).*")));
        }
        if (observations.isEmpty() && missing.isEmpty()) missing.add("请在同一句说明部位和形态，例如“老叶先均匀黄化”或“新叶脉间失绿”");
        if (observations.size()>10) { missing.add("一次最多分析 10 条观察，请精简描述"); observations.clear(); }
        return observations.stream().distinct().toList();
    }
    private static String label(Model model, Resource resource) {
        Statement label = resource.getProperty(RDFS.label);
        return label == null ? resource.getLocalName() : label.getString();
    }
    private static Answer stopped(String status, Question request, Slots slots, List<Stage> stages, String message) {
        for (String id : List.of("query", "reason", "evidence")) stages.add(new Stage(id,
                Map.of("query","查询本体","reason","执行规则","evidence","检索证据").get(id), "SKIPPED", "输入信息不完整或未收录，未执行此步骤。"));
        stages.add(new Stage("answer", "组织输出", "DONE", "返回澄清或未收录提示，不猜测结论。"));
        return new Answer(status, request.question(), slots, List.copyOf(stages), null, List.of(), message, "TEMPLATE");
    }
}
