package com.ohcp.cropagent.api;

import com.ohcp.cropagent.agent.DiagnosisService;
import com.ohcp.cropagent.agent.HeheConversation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ChatControllerTest {
    @Test void latestGreetingBypassesDiagnosisEvenWithPreviousDiseaseContext() throws Exception {
        var diagnosis = mock(DiagnosisService.class);
        var diseases = mock(com.ohcp.cropagent.knowledge.DiseaseRepository.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ChatController(diagnosis, new HeheConversation(), diseases,
                new com.ohcp.cropagent.agent.DiseaseAnswerComposer(),
                mock(com.ohcp.cropagent.agent.ChatAnswerComposer.class)))
            .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        var pending = mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"crop\":\"番茄\",\"question\":\"你好啊\",\"symptomText\":\"叶背白色霉层，果实褐色硬斑\"}")).andReturn();
        pending.getAsyncResult(5000);
        var response = mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(response.contains("我是禾禾"));
        assertTrue(response.contains("CHAT"));
        assertTrue(response.contains("event:done"));
        verifyNoInteractions(diagnosis);
        verifyNoInteractions(diseases);
    }
}
