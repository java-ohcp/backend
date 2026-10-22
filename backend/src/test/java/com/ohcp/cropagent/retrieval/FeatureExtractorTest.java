package com.ohcp.cropagent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeatureExtractorTest {
    private FeatureExtractor extractor() throws Exception {
        var kb = mock(KnowledgeRepository.class);
        when(kb.rules()).thenReturn(new ObjectMapper().readTree("""
            {"features":{"old_leaf":{"aliases":["老叶"]},"new_leaf":{"aliases":["新叶"]},
            "uniform_yellow":{"aliases":["发黄"]},"margin_scorch":{"aliases":["焦边"]}}}
            """));
        return new FeatureExtractor(kb);
    }
    @Test void negatedSymptomsAreNotEvidence() throws Exception {
        var hits = extractor().extract("老叶没有发黄，叶缘未见明显焦边。", null);
        assertFalse(hits.containsKey("uniform_yellow"));
        assertFalse(hits.containsKey("margin_scorch"));
    }
    @Test void scansPastFirstNegatedMentionAndKeepsLaterAffirmativeObservation() throws Exception {
        var hits = extractor().extract("老叶正常；新叶发黄，叶缘有焦边。", null);
        assertFalse(hits.containsKey("old_leaf"));
        assertTrue(hits.containsKey("new_leaf"));
        assertTrue(hits.containsKey("margin_scorch"));
        assertTrue(extractor().extract("未见发黄；三天后新叶发黄。", null).containsKey("uniform_yellow"));
    }
    @Test void normalNewLeavesDoNotNegateSymptomsInThePreviousClause() throws Exception {
        var hits = extractor().extract("老叶发黄，新叶正常。", null);
        assertTrue(hits.containsKey("old_leaf"));
        assertFalse(hits.containsKey("new_leaf"));
    }
}
