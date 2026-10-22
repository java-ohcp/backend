package com.ohcp.cropagent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 查询解析：把口语化描述转成结构化特征集合。
 *
 * 只做信息抽取，绝不做农学判断——判断交给规则矩阵。
 * 用别名表做字面命中，覆盖不了的情况（长句、比喻）交给向量通道，
 * 这也是为什么规则通道与向量通道必须并存。
 */
@Component
public class FeatureExtractor {

    private final KnowledgeRepository kb;

    public FeatureExtractor(KnowledgeRepository kb) {
        this.kb = kb;
    }

    /** 叶龄类特指的否定前后文："老叶还是绿的""新叶正常"这类表述不能当成症状命中。 */
    private static final String[] NEGATIONS = {"还是绿", "仍绿", "仍为绿", "正常", "没有", "未见", "无症", "不变", "完好"};

    /** 返回命中的特征码 -> 命中的原文证据（用于解释"因为你说……"）。 */
    public Map<String, String> extract(String text, String leafAge) {
        Map<String, String> hits = new LinkedHashMap<>();
        JsonNode features = kb.rules() == null ? null : kb.rules().get("features");
        if (features == null) {
            return hits;
        }

        String hay = text == null ? "" : text;
        features.fieldNames().forEachRemaining(code -> {
            JsonNode f = features.get(code);
            for (JsonNode alias : f.path("aliases")) {
                String a = alias.asText();
                if (a.isBlank()) {
                    continue;
                }
                int at = hay.indexOf(a);
                while (at >= 0 && (negatedBefore(hay, at)
                        || (isLeafAge(code) && negatedAfter(hay, at + a.length())))) {
                    at = hay.indexOf(a, at + a.length());
                }
                if (at < 0) continue;
                hits.putIfAbsent(code, a);
                break;
            }
        });

        // 结构化字段优先：用户在下拉框里明确选了叶龄，比文本里猜更可靠
        if (leafAge != null && !leafAge.isBlank()) {
            if (leafAge.contains("老")) {
                hits.put("old_leaf", "叶龄选择：老叶");
                hits.remove("new_leaf");
            } else if (leafAge.contains("新")) {
                hits.put("new_leaf", "叶龄选择：新叶");
                hits.remove("old_leaf");
            }
        }
        return hits;
    }

    public Set<String> codes(Map<String, String> hits) {
        return new LinkedHashSet<>(hits.keySet());
    }

    private boolean isLeafAge(String code) {
        return "old_leaf".equals(code) || "new_leaf".equals(code);
    }

    private boolean negatedBefore(String text, int at) {
        String before = text.substring(Math.max(0, at - 10), at).replaceAll("\\s", "");
        return before.matches("(?s).*(?:没有|未见|并无|未出现|不见|无)(?:明显的?|出现|发生)?$");
    }

    private boolean negatedAfter(String hay, int from) {
        int end = Math.min(hay.length(), from + 8);
        String window = hay.substring(from, end).split("[，。；！？,;]|但是|但|而", 2)[0];
        for (String neg : NEGATIONS) {
            if (window.contains(neg)) {
                return true;
            }
        }
        return false;
    }
}
