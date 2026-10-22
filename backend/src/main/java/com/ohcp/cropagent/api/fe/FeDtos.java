package com.ohcp.cropagent.api.fe;

import java.util.List;

/**
 * 前端契约适配层（面向 Desktop/前端/front-end 那份工程的类型定义）。
 *
 * 为什么要有这一层：前端的 types.ts 是自己定义的字段名（matchingFeatures / introduction /
 * agronomicControl / greenTip …），与我们对外契约（matchedFeatures / intro / agronomy / sizheng）
 * 不一致。两边都已成型，与其改一边再回归，不如在中间加一层映射——
 * 前端只改 VITE_API_BASE=/api/v1/fe 这一个环境变量，后端既有接口与文档完全不动。
 */
public class FeDtos {

    public record EvidenceItem(String title, String source, String excerpt) {
    }

    public record FeCandidate(
            String element,
            String elementName,
            Double confidence,
            String summary,
            List<String> matchingFeatures,
            List<EvidenceItem> evidence
    ) {
    }

    public record FeOption(String label, String expectedField, String value) {
    }

    public record FeFollowUp(String question, List<FeOption> options) {
    }

    public record FeDiagnose(
            String requestId,
            String sessionId,
            String decision,
            List<FeCandidate> candidates,
            FeFollowUp followUp,
            Boolean isDegraded,
            String degradedMode,
            String greenTip,
            com.ohcp.cropagent.api.dto.Dtos.Triage triage
    ) {
    }

    public record FePlanItem(String name, String amount, String method, String nutrientContent, String notes) {
    }

    public record FeAdvice(
            String element,
            String elementName,
            String mode,
            String introduction,
            List<String> agronomicControl,
            List<FePlanItem> organicPlan,
            List<FePlanItem> chemicalPlan,
            List<String> safetyReminders,
            List<SourceItem> sources
    ) {
    }

    public record SourceItem(String title, String url) {
    }

    public record FeKnowledge(
            String element,
            String elementName,
            String overview,
            List<String> symptoms,
            List<String> crops,
            List<String> advice,
            List<SourceItem> sources
    ) {
    }

    public record ServiceItem(String name, String status, String detail) {
    }

    public record FeHealth(String status, String checkedAt, List<ServiceItem> services) {
    }

    public record HotQueryItem(String crop, String symptom, Integer count) {
    }
}
