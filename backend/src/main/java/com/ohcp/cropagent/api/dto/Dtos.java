package com.ohcp.cropagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** 对外契约的唯一载体。领域模型不出现在这里。 */
public class Dtos {

    /** 诊断请求。只有 crop 与 symptomText 必填，其余缺失时走默认值兜底。 */
    public record SymptomQuery(
            @NotBlank(message = "作物不能为空") String crop,
            String part,
            String leafAge,
            @NotBlank(message = "症状描述不能为空")
            @Size(min = 4, max = 500, message = "症状描述需在 4~500 字之间") String symptomText,
            String env,
            String soil,
            String stage,
            String mode,
            String sessionId
    ) {
    }

    public record Evidence(String chunkId, String text, String sourceUrl) {
    }

    public record Candidate(
            String element,
            String elementName,
            double confidence,
            String confidenceLevel,
            List<String> matchedFeatures,
            List<Evidence> evidence,
            String differential
    ) {
    }

    public record FollowUp(String question, List<String> options, String expectedField) {
    }

    public record FactCheck(boolean passed, List<String> checked, List<String> unsupported) {
    }

    public record Advice(
            String elementName,
            String intro,
            List<String> agronomy,
            List<Map<String, Object>> organicPlan,
            List<Map<String, Object>> chemicalPlan,
            List<String> safety,
            String mode,
            String composedText,
            FactCheck factCheck,
            boolean llmUsed
    ) {
    }

    /** 单独取方案的请求（切换候选项或切换农户版/专业版时局部刷新）。 */
    public record AdviceQuery(
            @NotBlank(message = "作物不能为空") String crop,
            @NotBlank(message = "元素不能为空") String element,
            String mode
    ) {
    }

    public record Sizheng(String story, String value) {
    }

    public record Degraded(int level, String reason, List<String> capabilities) {
    }

    public record SourceRef(String title, String url) {}
    public record DiseaseCandidate(String id, String name, List<String> matchedFeatures) {}
    public record Triage(String category, String summary, List<DiseaseCandidate> candidates,
                         List<String> questions, List<String> actions, List<SourceRef> sources) {}

    public record DiagnoseResult(
            String requestId,
            String crop,
            List<Candidate> candidates,
            String decision,
            FollowUp followUp,
            Advice advice,
            Sizheng sizheng,
            String disclaimer,
            Degraded degraded,
            Triage triage
    ) {
    }
}
