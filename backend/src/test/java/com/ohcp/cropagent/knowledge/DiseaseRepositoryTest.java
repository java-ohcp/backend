package com.ohcp.cropagent.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiseaseRepositoryTest {

    private final List<Map<String, Object>> entries = List.of(
            Map.of(
                    "name", "稻瘟病",
                    "aliases", List.of("稻热病"),
                    "crops", List.of("水稻"),
                    "symptoms", List.of("梭形病斑", "中间灰白", "边缘褐色", "褐色坏死线", "暗绿色水渍斑点", "灰霉"),
                    "conditions", List.of("高温高湿")),
            Map.of(
                    "name", "纹枯病",
                    "aliases", List.of("云纹病"),
                    "crops", List.of("水稻"),
                    "symptoms", List.of("云纹状病斑", "菌核", "枯孕穗"),
                    "conditions", List.of("密植郁闭"))
    );

    @Test
    void matchesRiceBlastFromSymptomCombination() {
        var match = DiseaseRepository.match(entries,
                "叶片出现梭形病斑，中间灰白、边缘褐色，两端有褐色坏死线，潮湿长灰霉", "水稻");

        assertTrue(match.isPresent());
        assertEquals("稻瘟病", match.get().entry().get("name"));
        assertTrue(match.get().matchedFeatures().contains("梭形病斑"));
    }

    @Test
    void matchesByNameOrAlias() {
        var match = DiseaseRepository.match(entries, "水稻得了稻热病怎么办", "水稻");

        assertTrue(match.isPresent());
        assertEquals("稻瘟病", match.get().entry().get("name"));
    }

    @Test
    void ignoresASingleGenericSymptom() {
        assertFalse(DiseaseRepository.match(entries, "叶子边缘褐色", "水稻").isPresent());
    }

    @Test
    void ignoresDiseaseEntriesForOtherCrops() {
        assertFalse(DiseaseRepository.match(entries,
                "梭形病斑，中间灰白，边缘褐色，褐色坏死线", "番茄").isPresent());
    }

    @Test void negatedSymptomsCannotBypassTheDiagnosisEvidenceGate() {
        assertFalse(DiseaseRepository.match(entries, "未见梭形病斑，没有灰霉，叶片均匀发黄", "水稻").isPresent());
    }
}
