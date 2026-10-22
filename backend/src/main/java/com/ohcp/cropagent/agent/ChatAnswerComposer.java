package com.ohcp.cropagent.agent;

import com.ohcp.cropagent.api.dto.Dtos.Advice;
import com.ohcp.cropagent.api.dto.Dtos.DiagnoseResult;
import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 追问回答编排：根据问题类型从已命中的知识条目里挑重点，
 * 而不是每次都把六段式方案原样复读一遍。
 *
 * 所有内容仍来自知识库，模型只负责表达（由 AdviceComposer 处理）。
 */
@Service
public class ChatAnswerComposer {

    private final KnowledgeRepository kb;

    public ChatAnswerComposer(KnowledgeRepository kb) {
        this.kb = kb;
    }

    public String compose(Advice advice, String element, String question) {
        if (advice == null) {
            return null;
        }
        String q = question == null ? "" : question;

        if (containsAny(q, "为什么", "原因", "为何", "怎么会", "怎么回事", "原理")) {
            StringBuilder sb = new StringBuilder("本次判断为缺")
                    .append(advice.elementName()).append("。")
                    .append(advice.intro());
            String differential = element == null ? null : kb.differential(element);
            if (differential != null && !differential.isBlank()) {
                sb.append(" 容易混淆的情况：").append(differential).append("。");
            }
            return sb.toString();
        }

        if (containsAny(q, "症状", "怎么认", "如何判断", "怎么判断", "区别", "区分", "鉴别", "辨别", "不同", "差别", "表现")) {
            String differential = element == null ? null : kb.differential(element);
            return "缺" + advice.elementName() + "的典型表现：" + advice.intro()
                    + (differential == null || differential.isBlank() ? "" : " 需要区分的是：" + differential + "。");
        }

        if (containsAny(q, "用量", "怎么施", "如何施", "什么时候", "时机", "追肥", "施肥", "补什么", "用什么肥")) {
            return planText(advice);
        }

        return advice.composedText();
    }

    public String fallback(DiagnoseResult result) {
        String ask = result.followUp() == null ? "能再补充叶龄、部位和颜色变化吗？" : result.followUp().question();
        return "这个问题我暂时没有检索到足够可靠的资料。"
                + "我目前掌握的是作物缺素症和常见水稻病害，可以补充作物、叶龄、病斑形状和颜色后再问我。"
                + ask;
    }

    private String planText(Advice advice) {
        StringBuilder sb = new StringBuilder("针对缺").append(advice.elementName()).append("，可以这样补：");
        for (Map<String, Object> organic : nullSafe(advice.organicPlan())) {
            sb.append(text(organic.get("measure"))).append("，用量 ").append(text(organic.get("dosage")))
                    .append("，").append(text(organic.get("timing"))).append(text(organic.get("method"))).append("；");
        }
        for (Map<String, Object> chemical : nullSafe(advice.chemicalPlan())) {
            sb.append(text(chemical.get("fertilizer"))).append("，用量 ").append(text(chemical.get("dosage")))
                    .append("，").append(text(chemical.get("timing"))).append(text(chemical.get("method"))).append("；");
        }
        List<String> safety = advice.safety() == null ? List.of() : advice.safety();
        if (!safety.isEmpty()) {
            sb.append("注意事项：").append(String.join("；", safety)).append("。");
        }
        return sb.toString();
    }

    private <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
