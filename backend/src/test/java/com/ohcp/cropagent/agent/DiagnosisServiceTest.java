package com.ohcp.cropagent.agent;

import com.ohcp.cropagent.api.dto.Dtos.*;
import com.ohcp.cropagent.guardrail.ConfidenceScorer;
import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import com.ohcp.cropagent.retrieval.*;
import com.ohcp.cropagent.sizheng.SizhengService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DiagnosisServiceTest {
    private final FeatureExtractor extractor = mock(FeatureExtractor.class);
    private final RuleMatcher matcher = mock(RuleMatcher.class);
    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final ConfidenceScorer scorer = mock(ConfidenceScorer.class);
    private final KnowledgeRepository kb = mock(KnowledgeRepository.class);
    private final AdviceComposer composer = mock(AdviceComposer.class);
    private final SizhengService sizheng = mock(SizhengService.class);
    private final DiseaseTriageService triage = mock(DiseaseTriageService.class);
    private DiagnosisService service;
    private final SymptomQuery query = new SymptomQuery("水稻", "叶片", null, "老叶均匀黄化", null, null, null, "farmer", null);

    @BeforeEach void setup() {
        service = new DiagnosisService(extractor, matcher, retriever, scorer, kb, sizheng, composer, triage);
        ReflectionTestUtils.setField(service, "confirmThreshold", 0.65);
        ReflectionTestUtils.setField(service, "askThreshold", 0.4);
        ReflectionTestUtils.setField(service, "minMargin", 0.08);
        when(extractor.extract(anyString(), nullable(String.class))).thenReturn(Map.of("old_leaf", "老叶"));
        when(kb.appliesTo(anyString(), anyString())).thenReturn(true);
        when(kb.hasSource(anyString())).thenReturn(true);
        when(kb.evidenceText(anyString())).thenReturn("测试证据");
        when(kb.evidenceFor(anyString(), anyString())).thenAnswer(invocation -> List.of(
                new KnowledgeRepository.EvidenceDocument("test-" + invocation.getArgument(0), "测试元素", List.of("水稻"), "测试证据", "https://example.org/article")));
        when(sizheng.pick(anyString())).thenReturn(Map.of("story", "", "value", "科学施肥"));
        when(retriever.retrieve(anyString(), anyList())).thenReturn(new HybridRetriever.Result(Map.of("N", 1.0, "Mg", 0.9), Map.of(), List.of("rule", "template")));
    }

    private void scores(double first, double second) {
        var n = new RuleMatcher.Score("N", "氮", 0.8, List.of("老叶黄化"));
        var mg = new RuleMatcher.Score("Mg", "镁", 0.6, List.of("老叶"));
        when(matcher.scoreAll(anyMap())).thenReturn(List.of(n, mg));
        when(scorer.score(eq(n), anyMap(), nullable(Double.class))).thenReturn(first);
        when(scorer.score(eq(mg), anyMap(), nullable(Double.class))).thenReturn(second);
    }

    @Test void confirmedResultUsesPublishedSourceAndCropSpecificAdvice() {
        scores(0.85, 0.3);
        var result = service.diagnose(query);
        assertEquals("CONFIRMED", result.decision());
        assertEquals("test-N", result.candidates().get(0).evidence().get(0).chunkId());
        verify(composer).compose("N", "水稻", "farmer");
    }

    @Test void closeCandidatesAskForDistinguishingFeaturesWithoutAdvice() {
        scores(0.85, 0.82);
        var result = service.diagnose(query);
        assertEquals("NEED_MORE_INFO", result.decision());
        assertTrue(result.followUp().question().contains("候选表现接近"));
        assertNull(result.advice());
        verifyNoInteractions(composer);
    }

    @Test void uncertaintyNeverComposesOrPolishesFertilizerDosage() {
        scores(0.5, 0.2);
        assertEquals("NEED_MORE_INFO", service.diagnose(query).decision());
        verifyNoInteractions(composer);
    }

    @Test void conflictingLeafPositionsNeedClarificationEvenWithHighScore() {
        scores(0.9, 0.2);
        when(extractor.extract(anyString(), nullable(String.class))).thenReturn(Map.of("old_leaf", "老叶", "new_leaf", "新叶"));
        var result = service.diagnose(query);
        assertEquals("NEED_MORE_INFO", result.decision());
        assertEquals("leafAge", result.followUp().expectedField());
        verifyNoInteractions(composer);
    }

    @Test void ungroundedOrWrongCropRulesNeverBecomeCandidates() {
        scores(0.95, 0.8);
        when(kb.appliesTo(anyString(), anyString())).thenReturn(false);
        var result = service.diagnose(query);
        assertEquals("NO_MATCH", result.decision());
        assertTrue(result.candidates().isEmpty());
        verifyNoInteractions(composer);
    }

    @Test void semanticSimilarityAloneCannotConfirmDiagnosis() {
        when(matcher.scoreAll(anyMap())).thenReturn(List.of(new RuleMatcher.Score("N", "氮", 0, List.of())));
        when(retriever.retrieve(anyString(), anyList())).thenReturn(new HybridRetriever.Result(Map.of("N", 1.0), Map.of("N", 0.99), List.of("vector")));
        assertEquals("NO_MATCH", service.diagnose(query).decision());
        verifyNoInteractions(composer);
    }
    @Test void roundedDisplayScoresDoNotChangeCandidateRanking() {
        scores(0.851, 0.854);
        var result = service.diagnose(query);
        assertEquals("Mg", result.candidates().get(0).element());
        assertEquals("NEED_MORE_INFO", result.decision());
    }
}
