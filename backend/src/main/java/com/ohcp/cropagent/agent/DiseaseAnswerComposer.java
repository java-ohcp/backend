package com.ohcp.cropagent.agent;

import com.ohcp.cropagent.knowledge.DiseaseRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把病害知识条目拼成一段可直接朗读的回答。
 *
 * 和缺素方案一样，只组织知识库里已有的字段，不额外推断农药或用量。
 */
@Service
public class DiseaseAnswerComposer {

    public String compose(DiseaseRepository.DiseaseMatch match) {
        Map<String, Object> entry = match.entry();
        StringBuilder sb = new StringBuilder();

        sb.append("以下是「").append(text(entry.get("name"))).append("」的参考资料，不代表已经确诊。");
        String intro = text(entry.get("intro"));
        if (!intro.isBlank()) {
            sb.append(intro).append(" ");
        }

        // 只列这次真正命中的特征，避免把同义词全量铺开
        List<String> symptoms = strings(entry.get("symptoms"));
        List<String> keyPoints = match.matchedFeatures().stream()
                .filter(symptoms::contains)
                .distinct()
                .toList();
        append(sb, "识别要点", keyPoints.isEmpty() ? symptoms.stream().limit(6).toList() : keyPoints);
        append(sb, "发病条件", strings(entry.get("conditions")));
        append(sb, "栽培防治", strings(entry.get("prevention")));

        List<String> chemicals = new ArrayList<>();
        for (Map<String, Object> chemical : maps(entry.get("chemical"))) {
            String agent = text(chemical.get("agent"));
            String method = text(chemical.get("method"));
            if (agent.isBlank()) {
                continue;
            }
            chemicals.add(method.isBlank() ? agent : agent + "：" + method);
        }
        append(sb, "药剂防治", chemicals);

        append(sb, "安全提示", strings(entry.get("safety")));
        sb.append("病害判断和用药请结合当地植保部门指导，药剂用量以产品标签为准。");
        String source = text(entry.get("sourceUrl"));
        if (!source.isBlank()) sb.append("资料来源：").append(source);
        return sb.toString();
    }

    private void append(StringBuilder sb, String label, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("【").append(label).append("】").append(String.join("；", items)).append("。");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (item != null && !String.valueOf(item).isBlank()) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?>) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }
}
