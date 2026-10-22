package com.ohcp.cropagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohcp.cropagent.api.dto.Dtos.Advice;
import com.ohcp.cropagent.api.dto.Dtos.FactCheck;
import com.ohcp.cropagent.guardrail.FactValidator;
import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import com.ohcp.cropagent.llm.LlmPolisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 方案编排：模板拼出"事实骨架"，再视情况下交给模型润色。
 *
 * 三段式处理：
 *   1. 模板先把六段式字段拼成完整文本 —— 这一步永远执行，保证有货
 *   2. 模型润色（可选）—— 拿不到就跳过
 *   3. 事实校验 —— 润色后仍需通过校验才采纳，否则回退模板文本
 *
 * 第 3 步是关键：模型改得再顺，只要出现了知识库里没有的用量，就整段丢弃。
 * 这条闸门让"接不接模型"变成一个纯观感问题，而不是正确性问题。
 */
@Service
public class AdviceComposer {

    private static final Logger log = LoggerFactory.getLogger(AdviceComposer.class);

    private final KnowledgeRepository kb;
    private final FactValidator validator;
    private final LlmPolisher polisher;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdviceComposer(KnowledgeRepository kb, FactValidator validator, LlmPolisher polisher) {
        this.kb = kb;
        this.validator = validator;
        this.polisher = polisher;
    }

    @SuppressWarnings("unchecked")
    public Advice compose(String element, String mode) {
        return composeEntry(kb.adviceOf(element).orElse(null), mode);
    }

    public Advice compose(String element, String crop, String mode) {
        return composeEntry(kb.adviceOf(element, crop).orElse(null), mode);
    }

    @SuppressWarnings("unchecked")
    private Advice composeEntry(Map<String, Object> e, String mode) {
        if (e == null) {
            return null;
        }
        String useMode = (mode == null || mode.isBlank()) ? "farmer" : mode;
        boolean pro = "pro".equalsIgnoreCase(useMode);

        List<String> agronomy = (List<String>) e.getOrDefault("agronomy", List.of());
        List<Map<String, Object>> organic = (List<Map<String, Object>>) e.getOrDefault("organicPlan", List.of());
        List<Map<String, Object>> chemical = (List<Map<String, Object>>) e.getOrDefault("chemicalPlan", List.of());
        List<String> safety = (List<String>) e.getOrDefault("safety", List.of());

        String templateText = build(e, agronomy, organic, chemical, safety, pro);

        String evidence;
        try {
            evidence = mapper.writeValueAsString(e);
        } catch (Exception ex) {
            evidence = String.valueOf(e);
        }

        String finalText = templateText;
        boolean llmUsed = false;

        Optional<String> polished = polisher.polish(templateText);
        if (polished.isPresent()) {
            FactValidator.Result pr = validator.validate(polished.get(), evidence);
            if (pr.passed()) {
                finalText = polished.get();
                llmUsed = true;
            } else {
                log.warn("润色文本未通过事实校验，回退模板。未溯源：{}", pr.unsupported());
            }
        }

        FactValidator.Result r = validator.validate(finalText, evidence);
        if (!r.passed()) {
            log.warn("最终文本仍存在未溯源数值：{}", r.unsupported());
        }

        return new Advice(
                (String) e.get("elementName"),
                (String) e.get("intro"),
                agronomy,
                organic,
                chemical,
                safety,
                useMode,
                finalText,
                new FactCheck(r.passed(), r.checked(), r.unsupported()),
                llmUsed
        );
    }

    private String build(Map<String, Object> e, List<String> agronomy,
                         List<Map<String, Object>> organic,
                         List<Map<String, Object>> chemical,
                         List<String> safety, boolean pro) {
        StringBuilder sb = new StringBuilder();
        sb.append("本次判断为缺").append(e.get("elementName")).append("。");
        sb.append(e.get("intro")).append(" ");

        sb.append("【农艺调控】").append(String.join("；", agronomy)).append("。");

        sb.append("【有机与生物肥】");
        for (Map<String, Object> o : organic) {
            sb.append(o.get("measure")).append("，用量 ").append(o.get("dosage"))
                    .append("，").append(o.get("timing")).append(o.get("method")).append("；");
        }

        sb.append("【化学施肥】");
        for (Map<String, Object> c : chemical) {
            if (pro) {
                sb.append(c.get("fertilizer")).append("（").append(c.get("nutrient")).append("），");
            } else {
                sb.append(c.get("fertilizer")).append("，");
            }
            sb.append("用量 ").append(c.get("dosage")).append("，")
                    .append(c.get("timing")).append(c.get("method")).append("；");
            if (pro && c.get("caution") != null) {
                sb.append("注意：").append(c.get("caution")).append("；");
            }
        }

        sb.append("【安全与减量】").append(String.join("；", safety)).append("。");
        return sb.toString();
    }
}
