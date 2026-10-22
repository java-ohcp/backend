package com.ohcp.cropagent.agent;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ohcp.cropagent.api.dto.Dtos.*;

class DiseaseTriageServiceTest {
    private DiseaseTriageService service() {
        Path directory = List.of(Path.of("../data/kb"), Path.of("data/kb")).stream()
                .filter(path -> Files.exists(path.resolve("disease_triage.json"))).findFirst().orElseThrow();
        return new DiseaseTriageService(directory.toString());
    }
    private static final String USER_CASE = "叶片边缘出现暗绿色水渍状大病斑，湿度大时叶背长出一圈白色霉层；果实上长褐色硬斑，不软腐，后期发黑。茎秆发病处变褐缢缩，植株容易折断";
    @Test void suppliedTomatoCaseReturnsLateBlightDirectionWithSourceAndSpecificQuestions() {
        var result = service().assess("番茄", USER_CASE).orElseThrow();
        assertEquals("DISEASE_SUSPECTED", result.category());
        assertEquals(1, result.candidates().size());
        assertEquals("番茄晚疫病", result.candidates().get(0).name());
        assertTrue(result.candidates().get(0).matchedFeatures().size() >= 3);
        assertTrue(result.sources().get(0).url().startsWith("https://cast.caas.cn/"));
        assertFalse(String.join("", result.questions()).contains("发黄是从新叶"));
    }
    @Test void deficiencyAndNegatedDiseaseFeaturesDoNotTriggerDiseaseTriage() {
        assertTrue(service().assess("水稻", "下部老叶均匀黄化，植株矮小，分蘖减少").isEmpty());
        assertTrue(service().assess("番茄", "叶片均匀黄化，没有水渍状病斑，未见白色霉层").isEmpty());
    }
    @Test void incompatibleCropNeverGetsTheTomatoDiseaseName() {
        var result = service().assess("水稻", USER_CASE).orElseThrow();
        assertTrue(result.candidates().isEmpty());
        assertFalse(result.summary().contains("番茄晚疫病"));
    }
    @Test void grayMoldAndEarlyBlightHaveSeparateEvidenceCombinations() {
        assertEquals("番茄灰霉病", service().assess("番茄", "幼果腐烂，表面有灰色霉层").orElseThrow().candidates().get(0).name());
        assertEquals("番茄早疫病", service().assess("番茄", "老叶褐色病斑有同心轮纹，周围有黄色晕圈").orElseThrow().candidates().get(0).name());
        assertTrue(service().assess("番茄", "叶片出现褐色斑点").isEmpty());
    }
    @Test void riceSymptomsUseRiceSources() {
        var risk = service().assess("水稻", "叶片出现梭形病斑，中间灰白色，外围褐色并有黄色晕圈").orElseThrow();
        assertEquals("水稻稻瘟病", risk.candidates().get(0).name());
    }
    @Test void userCaseShortCircuitsNutrientRetrievalAndFertilizerGeneration() {
        var extractor = mock(com.ohcp.cropagent.retrieval.FeatureExtractor.class);
        var matcher = mock(com.ohcp.cropagent.retrieval.RuleMatcher.class);
        var retrieval = mock(com.ohcp.cropagent.retrieval.HybridRetriever.class);
        var scorer = mock(com.ohcp.cropagent.guardrail.ConfidenceScorer.class);
        var kb = mock(com.ohcp.cropagent.knowledge.KnowledgeRepository.class);
        var story = mock(com.ohcp.cropagent.sizheng.SizhengService.class);
        var composer = mock(AdviceComposer.class);
        var diagnosis = new DiagnosisService(extractor, matcher, retrieval, scorer, kb, story, composer, service());
        var result = diagnosis.diagnose(new SymptomQuery("番茄", null, null, USER_CASE, null, null, null, "farmer", null));
        assertEquals("DISEASE_SUSPECTED", result.decision());
        assertNull(result.advice()); assertTrue(result.candidates().isEmpty()); assertNotNull(result.triage());
        verifyNoInteractions(extractor, matcher, retrieval, scorer, composer);
    }
}
