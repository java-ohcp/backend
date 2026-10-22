package com.ohcp.cropagent.api;

import com.ohcp.cropagent.knowledge.EvidenceSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeSearchControllerTest {
    @Test void returnsActualAgentContractInsideExistingApiEnvelopeAndValidatesLimits() throws Exception {
        var service = mock(EvidenceSearchService.class);
        when(service.search("水稻", "老叶黄化", 8)).thenReturn(List.of(new EvidenceSearchService.Match(
                "test-N", "氮", List.of("水稻"), "测试证据", "https://example.org/article", 0.85)));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeSearchController(service)).build();
        mvc.perform(post("/api/v1/knowledge/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"crop\":\"水稻\",\"query\":\"老叶黄化\",\"topK\":8}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.matches[0].id").value("test-N"))
                .andExpect(jsonPath("$.data.matches[0].nutrient").value("氮"));
        mvc.perform(post("/api/v1/knowledge/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"crop\":\"水稻\",\"query\":\"老叶黄化\",\"topK\":1000}"))
                .andExpect(status().isBadRequest());
        verify(service, times(1)).search(anyString(), anyString(), anyInt());
    }
}
