package com.ohcp.cropagent.agent;

import com.ohcp.cropagent.api.dto.Dtos.*;
import com.ohcp.cropagent.guardrail.ConfidenceScorer;
import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import com.ohcp.cropagent.retrieval.FeatureExtractor;
import com.ohcp.cropagent.retrieval.HybridRetriever;
import com.ohcp.cropagent.retrieval.RuleMatcher;
import com.ohcp.cropagent.sizheng.SizhengService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 诊断编排：解析 → 三路混合检索 → 置信度 → 方案装配 → 输出。
 *
 * 排序用 RRF 融合结果，置信度用四因子加权——两者分工明确：
 * 融合回答"谁排前面"，置信度回答"这个结论有多可信"。
 */
@Service
public class DiagnosisService {

    private static final String DISCLAIMER =
            "匹配分数是检索与规则的启发式评分，不是确诊概率。请结合田间检测及当地测土配方施肥建议复核。";

    private final FeatureExtractor extractor;
    private final RuleMatcher matcher;
    private final HybridRetriever retriever;
    private final ConfidenceScorer scorer;
    private final KnowledgeRepository kb;
    private final SizhengService sizheng;
    private final AdviceComposer composer;

    @Value("${agent.confidence.confirm-threshold:0.65}")
    private double confirmThreshold;
    @Value("${agent.confidence.ask-threshold:0.40}")
    private double askThreshold;
    @Value("${agent.confidence.min-margin:0.08}")
    private double minMargin;

    public DiagnosisService(FeatureExtractor extractor, RuleMatcher matcher, HybridRetriever retriever,
                            ConfidenceScorer scorer, KnowledgeRepository kb,
                            SizhengService sizheng, AdviceComposer composer) {
        this.extractor = extractor;
        this.matcher = matcher;
        this.retriever = retriever;
        this.scorer = scorer;
        this.kb = kb;
        this.sizheng = sizheng;
        this.composer = composer;
    }

    public DiagnoseResult diagnose(SymptomQuery q) {
        String text = String.join(" ",
                nullToEmpty(q.symptomText()), nullToEmpty(q.part()),
                nullToEmpty(q.leafAge()), nullToEmpty(q.env()), nullToEmpty(q.soil()), nullToEmpty(q.stage()));

        // 阶段 1：查询解析
        Map<String, String> hits = extractor.extract(text, q.leafAge());

        // 阶段 2：规则打分 + 三路混合检索
        List<RuleMatcher.Score> scores = matcher.scoreAll(hits);
        HybridRetriever.Result rr = retriever.retrieve(text, scores);

        // 阶段 3：置信度
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Double> rawConfidence = new java.util.HashMap<>();
        double best = 0;
        String bestElement = null;

        for (RuleMatcher.Score s : scores) {
            // Rule metadata alone is not evidence: require an applicable, published knowledge entry.
            if (!kb.appliesTo(s.element(), q.crop()) || kb.evidenceText(s.element()).isBlank()
                    || !kb.hasSource(s.element())) {
                continue;
            }
            Double semantic = rr.semantic().containsKey(s.element()) ? rr.semanticOf(s.element()) : null;

            double conf;
            if (s.ruleScore() <= 0) {
                // 规则没命中、只靠 BM25/向量召回：不给四因子打分，
                // 否则作物先验会把"跟查询无关但常见"的元素顶上来。
                // 按融合排名给一个保守置信度，封顶 0.30，永远进不了 CONFIRMED。
                int rank = rankOf(rr.fused(), s.element());
                if (rank < 0) {
                    continue;
                }
                conf = Math.max(0.12, 0.30 - 0.05 * rank);
            } else {
                conf = scorer.score(s, hits, semantic);
            }

            if (conf < 0.05) {
                continue;
            }
            rawConfidence.put(s.element(), conf);
            candidates.add(new Candidate(
                    s.element(), s.elementName(), round(conf), scorer.level(conf),
                    s.matched(),
                    kb.evidenceFor(s.element(), q.crop()).stream().limit(8)
                            .map(doc -> new Evidence(doc.id(), doc.text(), doc.sourceUrl())).toList(),
                    kb.differential(s.element())
            ));
            if (conf > best) {
                best = conf;
                bestElement = s.element();
            }
        }

        // 置信度为主、融合分为辅，避免"召回好但判不准"的候选排到前面
        candidates.sort(Comparator
                .comparingDouble((Candidate candidate) -> rawConfidence.get(candidate.element())).reversed()
                .thenComparing(c -> rr.fused().getOrDefault(c.element(), 0.0), Comparator.reverseOrder()));
        if (candidates.size() > 3) {
            candidates = new ArrayList<>(candidates.subList(0, 3));
        }
        if (!candidates.isEmpty()) {
            bestElement = candidates.get(0).element();
            best = rawConfidence.get(bestElement);
        }

        // 阶段 5：输出装配
        String decision;
        FollowUp followUp = null;
        Advice advice;
        double second = candidates.size() > 1 ? rawConfidence.get(candidates.get(1).element()) : 0;
        boolean ambiguous = candidates.size() > 1 && best - second < minMargin;
        boolean conflictingPosition = hits.containsKey("old_leaf") && hits.containsKey("new_leaf");
        boolean unknownCrop = q.crop() == null || q.crop().isBlank() || "未指明作物".equals(q.crop());

        if (best >= confirmThreshold && !ambiguous && !conflictingPosition && !unknownCrop) {
            decision = "CONFIRMED";
            advice = composer.compose(bestElement, q.crop(), q.mode());
        } else if (best >= askThreshold || conflictingPosition || unknownCrop) {
            decision = "NEED_MORE_INFO";
            followUp = followUpFor(q, hits, ambiguous);
            advice = null;
        } else {
            decision = "NO_MATCH";
            followUp = followUpFor(q, hits, false);
            advice = null;
        }

        Sizheng sz = null;
        if (bestElement != null) {
            Map<String, String> picked = sizheng.pick(bestElement);
            sz = new Sizheng(picked.get("story"), picked.get("value"));
        }

        return new DiagnoseResult(
                "d-" + java.util.UUID.randomUUID().toString(),
                q.crop(),
                candidates,
                decision,
                followUp,
                advice,
                sz,
                DISCLAIMER,
                degradedOf(rr.capabilities())
        );
    }

    private FollowUp followUpFor(SymptomQuery q, Map<String, String> hits, boolean ambiguous) {
        if (q.crop() == null || q.crop().isBlank() || "未指明作物".equals(q.crop())) {
            return new FollowUp("请先说明是什么作物，再结合症状复核。", List.of(), "crop");
        }
        if ((hits.containsKey("old_leaf") && hits.containsKey("new_leaf"))
                || (!hits.containsKey("old_leaf") && !hits.containsKey("new_leaf"))) {
            return new FollowUp("发黄是从新叶还是老叶开始的？", List.of("新叶", "老叶", "全株"), "leafAge");
        }
        if (ambiguous) {
            return new FollowUp("候选表现接近：黄化是整叶均匀发生，还是叶脉间失绿、叶脉仍绿？",
                    List.of("整叶均匀黄化", "脉间失绿且叶脉仍绿", "叶缘焦枯", "尚不能确定"), "symptomText");
        }
        if (q.part() == null || q.part().isBlank()) {
            return new FollowUp("症状主要出现在哪个部位？", List.of("叶片", "茎", "根系", "果实", "生长点"), "part");
        }
        return new FollowUp("叶片上有斑点或焦边吗？叶脉还是绿色的吗？",
                List.of("有斑点", "叶缘焦枯", "脉间失绿", "都没有"), "symptomText");
    }

    /** 能力缺失如实上报，前端据此显示降级角标。 */
    private Degraded degradedOf(List<String> capabilities) {
        boolean hasVector = capabilities.contains("vector");
        boolean hasBm25 = capabilities.contains("bm25");
        if (hasVector) {
            return new Degraded(0, null, capabilities);
        }
        if (hasBm25) {
            return new Degraded(2, "语义检索未启用，当前为规则 + 关键词检索", capabilities);
        }
        return new Degraded(3, "仅规则通道可用，检索能力受限", capabilities);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 融合结果里的排名序号，找不到返回 -1。 */
    private int rankOf(Map<String, Double> fused, String element) {
        int i = 0;
        for (String k : fused.keySet()) {
            if (k.equals(element)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
