package com.ohcp.cropagent.api.fe;

import com.ohcp.cropagent.agent.AdviceComposer;
import com.ohcp.cropagent.agent.DiagnosisService;
import com.ohcp.cropagent.analytics.AnalyticsService;
import com.ohcp.cropagent.api.ApiResponse;
import com.ohcp.cropagent.api.dto.Dtos.*;
import com.ohcp.cropagent.api.fe.FeDtos.*;
import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import com.ohcp.cropagent.llm.LlmPolisher;
import com.ohcp.cropagent.retrieval.Bm25Index;
import com.ohcp.cropagent.retrieval.VectorIndex;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端契约适配接口。
 *
 * 只做形状转换，不含任何业务逻辑——判断、置信度、方案、校验全在既有的服务里跑。
 * 这样既保住前端不用改代码，也避免两套逻辑各算一遍导致口径分裂。
 */
@RestController
@RequestMapping("/api/v1/fe")
public class FeController {

    private final DiagnosisService diagnosisService;
    private final AdviceComposer composer;
    private final KnowledgeRepository kb;
    private final VectorIndex vectorIndex;
    private final Bm25Index bm25Index;
    private final LlmPolisher polisher;
    private final AnalyticsService analytics;

    public FeController(DiagnosisService diagnosisService, AdviceComposer composer,
                        KnowledgeRepository kb, VectorIndex vectorIndex, Bm25Index bm25Index,
                        LlmPolisher polisher, AnalyticsService analytics) {
        this.diagnosisService = diagnosisService;
        this.composer = composer;
        this.kb = kb;
        this.vectorIndex = vectorIndex;
        this.bm25Index = bm25Index;
        this.polisher = polisher;
        this.analytics = analytics;
    }

    @PostMapping("/diagnose")
    public ApiResponse<FeDiagnose> diagnose(@Valid @RequestBody SymptomQuery query) {
        long t0 = System.currentTimeMillis();
        DiagnoseResult r = diagnosisService.diagnose(query);

        List<FeCandidate> candidates = new ArrayList<>();
        for (Candidate c : r.candidates()) {
            String source = c.evidence().isEmpty() ? null : c.evidence().get(0).sourceUrl();
            String excerpt = c.evidence().isEmpty() ? null : c.evidence().get(0).text();
            candidates.add(new FeCandidate(
                    c.element(), c.elementName(), c.confidence(),
                    c.differential(),
                    c.matchedFeatures(),
                    List.of(new EvidenceItem(c.elementName() + " 缺素症", source, excerpt))
            ));
        }

        FeFollowUp followUp = null;
        if (r.followUp() != null) {
            List<FeOption> opts = new ArrayList<>();
            for (String o : r.followUp().options()) {
                opts.add(new FeOption(o, r.followUp().expectedField(), o));
            }
            followUp = new FeFollowUp(r.followUp().question(), opts);
        }

        String greenTip = r.sizheng() == null ? null
                : (r.sizheng().story() == null ? r.sizheng().value()
                   : r.sizheng().story() + " " + r.sizheng().value());

        String topElement = r.candidates().isEmpty() ? null : r.candidates().get(0).element();
        analytics.record(query.crop(), query.symptomText(), topElement);

        // 前端诊断请求不带 sessionId，若原样返回 null，前端的「继续追问」面板
        // (v-if="result?.sessionId") 永远不会出现。这里补一个可追溯的会话号，
        // 让对话入口真正可达；带上游传入的值时保持原样。
        String sessionId = query.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "s-" + r.requestId();
        }

        FeDiagnose data = new FeDiagnose(
                r.requestId(), sessionId, r.decision(), candidates, followUp,
                r.degraded() != null && r.degraded().level() > 0,
                r.degraded() == null ? null : r.degraded().reason(),
                greenTip);

        ApiResponse<FeDiagnose> resp = ApiResponse.ok(data);
        resp.setCostMs(System.currentTimeMillis() - t0);
        return resp;
    }

    @PostMapping("/advice")
    public ApiResponse<FeAdvice> advice(@Valid @RequestBody AdviceQuery query) {
        Advice a = composer.compose(query.element(), query.mode());
        if (a == null) {
            return ApiResponse.fail(2002, "未收录该元素：" + query.element());
        }

        List<FePlanItem> organic = new ArrayList<>();
        for (Map<String, Object> o : a.organicPlan()) {
            organic.add(new FePlanItem(
                    str(o.get("measure")), str(o.get("dosage")), str(o.get("method")),
                    str(o.get("dosage")), str(o.get("timing"))));
        }
        List<FePlanItem> chemical = new ArrayList<>();
        for (Map<String, Object> c : a.chemicalPlan()) {
            chemical.add(new FePlanItem(
                    str(c.get("fertilizer")), str(c.get("dosage")), str(c.get("method")),
                    str(c.get("nutrient")),
                    str(c.get("timing")) + (c.get("caution") == null ? "" : "｜" + c.get("caution"))));
        }

        Map<String, Object> entry = kb.adviceOfIgnoreCase(query.element()).orElse(Map.of());
        List<SourceItem> sources = List.of(new SourceItem(
                str(entry.get("publisher")), str(entry.get("sourceUrl"))));

        FeAdvice data = new FeAdvice(
                query.element(), a.elementName(), a.mode(), a.intro(),
                a.agronomy(), organic, chemical, a.safety(), sources);
        return ApiResponse.ok(data);
    }

    @GetMapping("/knowledge/{element}")
    public ApiResponse<FeKnowledge> knowledge(@PathVariable String element) {
        Map<String, Object> e = kb.adviceOfIgnoreCase(element).orElse(null);
        if (e == null) {
            return ApiResponse.fail(2002, "未收录该元素：" + element);
        }
        List<String> symptoms = new ArrayList<>();
        symptoms.addAll(list(e.get("colorPattern")));
        symptoms.addAll(list(e.get("morphology")));

        FeKnowledge data = new FeKnowledge(
                str(e.get("element")), str(e.get("elementName")), str(e.get("intro")),
                symptoms, list(e.get("crops")), list(e.get("agronomy")),
                List.of(new SourceItem(str(e.get("publisher")), str(e.get("sourceUrl")))));
        return ApiResponse.ok(data);
    }

    @GetMapping("/stats/hot")
    public ApiResponse<List<HotQueryItem>> hot() {
        List<HotQueryItem> rows = new ArrayList<>();
        for (AnalyticsService.Row r : analytics.hot()) {
            rows.add(new HotQueryItem(r.crop(), r.symptom(), r.count()));
        }
        if (rows.isEmpty()) {
            // 空看板不好看，给两条演示占位
            rows.add(new HotQueryItem("水稻", "下部老叶均匀发黄", 1));
            rows.add(new HotQueryItem("柑橘", "新叶脉间发黄", 1));
        }
        return ApiResponse.ok(rows);
    }

    @GetMapping("/health")
    public ApiResponse<FeHealth> health() {
        List<ServiceItem> services = new ArrayList<>();
        boolean kbUp = kb.size() > 0;
        services.add(new ServiceItem("知识库", kbUp ? "UP" : "DOWN", kb.size() + " 条知识条目"));
        services.add(new ServiceItem("BM25 索引", bm25Index.size() > 0 ? "UP" : "DOWN",
                bm25Index.size() + " 篇文档"));
        services.add(new ServiceItem("向量索引", vectorIndex.available() ? "UP" : "DOWN",
                vectorIndex.size() + " 条切片" + (vectorIndex.available() ? "" : "（未生成向量文件）")));
        services.add(new ServiceItem("大模型", polisher.enabled() ? "UP" : "DEGRADED",
                polisher.enabled() ? "DeepSeek 已接入" : "未配置，使用模板文案"));

        FeHealth data = new FeHealth(kbUp ? "UP" : "DOWN",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                services);
        return ApiResponse.ok(data);
    }

    @PostMapping("/knowledge/reload")
    public ApiResponse<Map<String, Object>> reload() {
        kb.load();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reloaded", kb.size());
        return ApiResponse.ok(data);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Object o) {
        return o instanceof List ? (List<String>) o : List.of();
    }
}
