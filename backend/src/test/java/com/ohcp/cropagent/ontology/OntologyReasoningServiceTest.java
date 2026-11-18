package com.ohcp.cropagent.ontology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.ohcp.cropagent.ontology.OntologyReasoningService.*;

class OntologyReasoningServiceTest {
    @TempDir Path temp;
    private final Path assets = Path.of("../data/ontology");
    private final OntologyReasoningService service = new OntologyReasoningService(assets.toString());
    private Request observation(String part, String pattern) {
        return new Request("Rice", List.of(new ObservedSymptom(part, pattern, true)));
    }
    private Candidate candidate(Result result, String id) {
        return result.candidates().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }
    private OntologyReasoningService copiedAssets() throws Exception {
        for (String file : List.of("ohcp-ontology.ttl", "reasoning-schema.ttl", "ohcp.rules"))
            Files.copy(assets.resolve(file), temp.resolve(file));
        Files.createDirectory(temp.resolve("queries"));
        Files.copy(assets.resolve("queries/reasoning-candidates.rq"), temp.resolve("queries/reasoning-candidates.rq"));
        return new OntologyReasoningService(temp.toString());
    }

    @Test void derivesCandidateAndExclusionWithExactRuleAndPremises() throws Exception {
        Result result = service.analyze(observation("OldLeaf", "UniformChlorosis"));
        assertEquals("CANDIDATES", result.status());
        assertEquals("GENERIC_ONLY", result.scope());
        assertEquals("SUPPORTED", candidate(result, "NitrogenDeficiency").decision());
        assertTrue(candidate(result, "NitrogenDeficiency").prioritized());
        Candidate excluded = candidate(result, "MagnesiumDeficiency");
        assertEquals("EXCLUDED", excluded.decision());
        assertTrue(excluded.reason().contains("脉间失绿"));
        String source = Files.readString(assets.resolve("ohcp.rules"));
        for (Step step : result.steps()) {
            assertNotNull(step.ruleText());
            assertTrue(source.contains(step.ruleText()));
            assertFalse(step.conclusion().asserted(), "conclusion must be new, not pre-asserted");
            assertFalse(step.premises().isEmpty());
        }
        assertEquals(4, result.query().rows().size());
        assertEquals(Files.readString(assets.resolve("queries/reasoning-candidates.rq")), result.query().sparql());
    }

    @Test void ruleDeletionChangesCandidateAndExclusionIndependently() throws Exception {
        OntologyReasoningService copy = copiedAssets();
        Path file = temp.resolve("ohcp.rules");
        String rules = Files.readString(file);
        Files.writeString(file, rules.replaceAll("(?s)\\[excludeInterveinalForUniform:.*?]", ""));
        assertEquals("UNSUPPORTED", candidate(copy.analyze(observation("OldLeaf", "UniformChlorosis")), "MagnesiumDeficiency").decision());
        Files.writeString(file, rules.replaceAll("(?s)\\[matchSymptom:.*?]", ""));
        assertEquals("NO_MATCH", copy.analyze(observation("OldLeaf", "UniformChlorosis")).status());
    }

    @Test void conflictsOnMutuallyExclusiveFirstOccurrenceNotMerelyTwoLeafAges() throws Exception {
        var bothFirst = new Request("Rice", List.of(new ObservedSymptom("OldLeaf", "UniformChlorosis", true),
                new ObservedSymptom("YoungLeaf", "InterveinalChlorosis", true)));
        Result conflict = service.analyze(bothFirst);
        assertEquals("CONFLICT", conflict.status());
        assertTrue(conflict.conflicts().stream().anyMatch(item -> item.type().equals("FirstAffectedPartConflict")));
        assertTrue(conflict.candidates().stream().allMatch(item -> item.decision().equals("WITHHELD")));
        var concurrentSymptoms = new Request("Rice", List.of(new ObservedSymptom("OldLeaf", "UniformChlorosis", true),
                new ObservedSymptom("YoungLeaf", "InterveinalChlorosis", false)));
        assertTrue(service.analyze(concurrentSymptoms).conflicts().isEmpty());
    }

    @Test void classifiesNewObservationAndInheritsSymptomType() throws Exception {
        Result result = service.analyze(observation("OldLeaf", "UniformChlorosis"));
        assertEquals(1, result.classifications().size());
        assertTrue(result.classifications().get(0).types().contains("https://ohcp.example/ontology#Discoloration"));
        assertTrue(result.classifications().get(0).types().contains("https://ohcp.example/ontology#Symptom"));
        assertTrue(result.classifications().get(0).steps().stream().anyMatch(step -> step.ruleId().equals("classifySymptom")));
    }

    @Test void detectsDisjointTypesIncludingInheritedTypes() throws Exception {
        OntologyReasoningService copy = copiedAssets();
        Files.writeString(temp.resolve("ohcp-ontology.ttl"), "\n:Nitrogen a :MicroNutrient .\n", java.nio.file.StandardOpenOption.APPEND);
        Result result = copy.analyze(observation("OldLeaf", "UniformChlorosis"));
        assertEquals("CONFLICT", result.status());
        assertTrue(result.conflicts().stream().anyMatch(item -> item.type().equals("DisjointTypeConflict")));
    }

    @Test void avoidsExclusionWhenInterveinalSymptomsAreAlsoObserved() throws Exception {
        var request = new Request(null, List.of(new ObservedSymptom("OldLeaf", "UniformChlorosis", false),
                new ObservedSymptom("OldLeaf", "InterveinalChlorosis", false)));
        assertEquals("SUPPORTED", candidate(service.analyze(request), "MagnesiumDeficiency").decision());
    }

    @Test void refusesUnknownAndWrongKindOfEntityAndEmptyInput() throws Exception {
        assertEquals("NOT_COVERED", service.analyze(observation("UnknownPart", "UniformChlorosis")).status());
        assertEquals("NOT_COVERED", service.analyze(observation("Rice", "UniformChlorosis")).status());
        assertEquals("NOT_COVERED", service.analyze(new Request("UnknownCrop", observation("OldLeaf", "UniformChlorosis").observations())).status());
        assertThrows(IllegalArgumentException.class, () -> service.analyze(new Request(null, List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(observation("../OldLeaf", "UniformChlorosis")));
    }

    @Test void requestsDoNotPersistOrLeakObservationFacts() throws Exception {
        service.analyze(observation("OldLeaf", "UniformChlorosis"));
        Result next = service.analyze(observation("YoungLeaf", "InterveinalChlorosis"));
        assertEquals("UNSUPPORTED", candidate(next, "NitrogenDeficiency").decision());
        assertEquals("SUPPORTED", candidate(next, "IronDeficiency").decision());
        assertFalse(Files.readString(assets.resolve("ohcp-ontology.ttl")).contains("urn:ohcp:observation"));
    }

    @Test void mobilityPriorityDependsOnExternalRuleAndFirstOccurrence() throws Exception {
        Result noFirst = service.analyze(new Request(null, List.of(new ObservedSymptom("OldLeaf", "UniformChlorosis", false))));
        assertFalse(candidate(noFirst, "NitrogenDeficiency").prioritized());
        OntologyReasoningService copy = copiedAssets();
        Path rulesFile = temp.resolve("ohcp.rules");
        Files.writeString(rulesFile, Files.readString(rulesFile).replaceAll("(?s)\\[prioritizeMobileNutrient:.*?]", ""));
        assertFalse(candidate(copy.analyze(observation("OldLeaf", "UniformChlorosis")), "NitrogenDeficiency").prioritized());
    }
}
