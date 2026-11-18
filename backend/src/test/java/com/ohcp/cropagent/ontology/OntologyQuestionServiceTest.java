package com.ohcp.cropagent.ontology;
import com.ohcp.cropagent.knowledge.EvidenceSearchService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OntologyQuestionServiceTest {
    private final EvidenceSearchService evidence = mock(EvidenceSearchService.class);
    private final OntologyQuestionService service = new OntologyQuestionService(
            new OntologyReasoningService("../data/ontology"), evidence, "../data/ontology");
    @Test void completesFiveStagesAndFiltersEvidenceToSupportedNutrient() throws Exception {
        when(evidence.search(eq("水稻"), anyString(), eq(20))).thenReturn(List.of(
                new EvidenceSearchService.Match("n1","氮",List.of("水稻"),"测试证据","https://example.org/n",.8),
                new EvidenceSearchService.Match("mg1","镁",List.of("水稻"),"排除元素证据","https://example.org/mg",.9)));
        var result = service.ask(new OntologyQuestionService.Question("水稻","分蘖期，老叶先均匀黄化，植株长势偏弱。"));
        assertEquals("CANDIDATES",result.status());
        assertEquals(5,result.stages().size());
        assertEquals("分蘖期",result.slots().stage());
        assertEquals("Rice",result.slots().cropId());
        assertEquals("OldLeaf",result.slots().observations().get(0).part());
        assertEquals("n1",result.evidence().get(0).id());
        assertEquals(1,result.evidence().size());
        assertTrue(result.answer().contains("缺氮"));
        assertTrue(result.answer().contains("不是该作物的确诊"));
        assertEquals("TEMPLATE",result.mode());
    }
    @Test void conflictsSkipEvidenceAndDoNotGiveDiagnosticAnswer() throws Exception {
        var result=service.ask(new OntologyQuestionService.Question("水稻","老叶先均匀黄化；新叶先脉间失绿。"));
        assertEquals("CONFLICT",result.status());
        assertTrue(result.answer().contains("冲突"));
        assertEquals("SKIPPED",result.stages().get(3).status());
        verifyNoInteractions(evidence);
    }
    @Test void unknownCropAndAmbiguousOrNegatedSymptomsAskInsteadOfGuessing() throws Exception {
        assertEquals("NOT_COVERED",service.ask(new OntologyQuestionService.Question("未知作物","老叶先均匀黄化")).status());
        for(String text:List.of("叶子发黄了", "老叶不是均匀黄化", "老叶新叶先均匀黄化", "老叶均匀黄化；新叶发黄", "老叶叶脉仍绿", "玉米老叶先均匀黄化")) {
            var result=service.ask(new OntologyQuestionService.Question("水稻",text));
            assertEquals("NEED_MORE_INFO",result.status(),text);
            assertNull(result.reasoning());
            assertEquals(5,result.stages().size());
        }
        verifyNoInteractions(evidence);
    }
    @Test void evidenceFailureRetainsReasoningAndDoesNotInventDetails() throws Exception {
        when(evidence.search(anyString(),anyString(),anyInt())).thenThrow(new IllegalStateException("offline"));
        var result=service.ask(new OntologyQuestionService.Question("水稻","老叶先均匀黄化"));
        assertEquals("CANDIDATES",result.status());
        assertEquals("UNAVAILABLE",result.stages().get(3).status());
        assertTrue(result.evidence().isEmpty());
        assertTrue(result.answer().contains("不提供施肥用量"));
    }
}
