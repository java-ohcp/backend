package com.ohcp.cropagent.knowledge;

import com.ohcp.cropagent.guardrail.ConfidenceScorer;
import com.ohcp.cropagent.retrieval.FeatureExtractor;
import com.ohcp.cropagent.retrieval.HybridRetriever;
import com.ohcp.cropagent.retrieval.RuleMatcher;
import org.springframework.stereotype.Service;
import java.util.*;

/** Retrieval-only seam for the separately packaged Java agent; never invokes advice generation. */
@Service
public class EvidenceSearchService {
    public record Match(String id, String nutrient, List<String> crops, String text, String sourceUrl, double score) {}
    private final KnowledgeRepository kb;
    private final FeatureExtractor extractor;
    private final RuleMatcher matcher;
    private final HybridRetriever retriever;
    private final ConfidenceScorer scorer;

    public EvidenceSearchService(KnowledgeRepository kb, FeatureExtractor extractor, RuleMatcher matcher,
                                 HybridRetriever retriever, ConfidenceScorer scorer) {
        this.kb = kb; this.extractor = extractor; this.matcher = matcher; this.retriever = retriever; this.scorer = scorer;
    }

    public List<Match> search(String crop, String query, int topK) {
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("topK must be 1–20");
        Map<String, String> features = extractor.extract(query, null);
        List<RuleMatcher.Score> scores = matcher.scoreAll(features);
        HybridRetriever.Result result = retriever.retrieve(query, scores);
        List<Match> matches = new ArrayList<>();
        for (RuleMatcher.Score score : scores) {
            Double semantic = result.semantic().get(score.element());
            if (score.ruleScore() <= 0 && semantic == null) continue;
            double similarity = score.ruleScore() <= 0 ? Math.min(0.30, semantic) : scorer.score(score, features, semantic);
            if (!Double.isFinite(similarity) || similarity <= 0) continue;
            double normalized = Math.max(0, Math.min(1, similarity));
            for (KnowledgeRepository.EvidenceDocument document : kb.evidenceFor(score.element(), crop)) {
                matches.add(new Match(document.id(), document.nutrient(), document.crops(), document.text(), document.sourceUrl(), normalized));
            }
        }
        return matches.stream().sorted(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(Match::id)).limit(topK).toList();
    }
}
