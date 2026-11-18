package com.ohcp.cropagent.ontology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

class OntologyControllerTest {
    @TempDir Path emptyAssets;
    private MockMvc mvc(String directory) {
        return MockMvcBuilders.standaloneSetup(new OntologyController(new OntologyService(directory),
                new OntologyReasoningService(directory))).build();
    }

    @Test void returnsQueryAndRealReasoningInsideApiEnvelope() throws Exception {
        MockMvc mvc = mvc("../data/ontology");
        mvc.perform(get("/api/v1/ontology/query/candidates").param("id", "OldLeafUniformChlorosis"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows", hasSize(1)));
        mvc.perform(post("/api/v1/ontology/analyze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"crop\":\"Rice\",\"observations\":[{\"part\":\"OldLeaf\",\"pattern\":\"UniformChlorosis\",\"first\":true}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CANDIDATES"))
                .andExpect(jsonPath("$.data.candidates[?(@.id == 'MagnesiumDeficiency')].decision", contains("EXCLUDED")))
                .andExpect(jsonPath("$.data.steps", not(empty())));
    }

    @Test void rejectsMalformedAndOversizedInputsWithoutInternalErrors() throws Exception {
        MockMvc mvc = mvc("../data/ontology");
        for (String body : new String[]{"{}", "{\"observations\":[]}", "{\"observations\":[null]}",
                "{\"observations\":[{\"part\":\"../x\",\"pattern\":\"UniformChlorosis\"}]}", "{broken"}) {
            mvc.perform(post("/api/v1/ontology/analyze").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        String item = "{\"part\":\"OldLeaf\",\"pattern\":\"UniformChlorosis\"}";
        mvc.perform(post("/api/v1/ontology/analyze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"observations\":[" + String.join(",", java.util.Collections.nCopies(11, item)) + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test void reportsUnavailableAssetsWithoutPretendingNoMatch() throws Exception {
        mvc(emptyAssets.toString()).perform(post("/api/v1/ontology/analyze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"observations\":[{\"part\":\"OldLeaf\",\"pattern\":\"UniformChlorosis\"}]}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(2001));
    }
}
