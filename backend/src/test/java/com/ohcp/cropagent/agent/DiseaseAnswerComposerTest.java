package com.ohcp.cropagent.agent;

import com.ohcp.cropagent.knowledge.DiseaseRepository.DiseaseMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DiseaseAnswerComposerTest {

    @Test
    void composesAllKnowledgeSections() {
        Map<String, Object> entry = Map.of(
                "name", "稻瘟病",
                "intro", "稻瘟病由稻瘟病菌引起。",
                "symptoms", List.of("梭形病斑", "边缘褐色"),
                "conditions", List.of("高温高湿"),
                "prevention", List.of("选用抗病品种"),
                "chemical", List.of(Map.of("agent", "三环唑", "method", "按标签用量")),
                "safety", List.of("注意安全间隔期"));

        String answer = new DiseaseAnswerComposer().compose(new DiseaseMatch(entry, 10, List.of("梭形病斑")));

        assertTrue(answer.contains("稻瘟病"));
        assertTrue(answer.contains("【识别要点】"));
        assertTrue(answer.contains("【发病条件】"));
        assertTrue(answer.contains("【栽培防治】"));
        assertTrue(answer.contains("【药剂防治】三环唑"));
        assertTrue(answer.contains("【安全提示】"));
        assertTrue(answer.contains("产品标签"));
    }
}
