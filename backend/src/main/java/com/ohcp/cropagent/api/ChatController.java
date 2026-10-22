package com.ohcp.cropagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohcp.cropagent.agent.DiagnosisService;
import com.ohcp.cropagent.agent.HeheConversation;
import com.ohcp.cropagent.agent.ChatAnswerComposer;
import com.ohcp.cropagent.agent.DiseaseAnswerComposer;
import com.ohcp.cropagent.knowledge.DiseaseRepository;
import com.ohcp.cropagent.api.dto.Dtos.Candidate;
import com.ohcp.cropagent.api.dto.Dtos.DiagnoseResult;
import com.ohcp.cropagent.api.dto.Dtos.SymptomQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 流式对话。事件名固定六种：meta / candidate / token / sizheng / done / error。
 *
 * 注意：这里用 POST 而非 GET，因为要带上下文。前端的 EventSource 只支持 GET 且不能带 body，
 * 所以前端必须用 fetch + ReadableStream 手工解析 SSE 帧——这一点对接文档里已写明。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int CHUNK = 12;
    private static final long CHUNK_DELAY_MS = 28;

    private final DiagnosisService service;
    private final HeheConversation conversation;
    private final DiseaseRepository diseases;
    private final DiseaseAnswerComposer diseaseComposer;
    private final ChatAnswerComposer chatComposer;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(DiagnosisService service, HeheConversation conversation, DiseaseRepository diseases,
                          DiseaseAnswerComposer diseaseComposer, ChatAnswerComposer chatComposer) {
        this.service = service;
        this.conversation = conversation;
        this.diseases = diseases;
        this.diseaseComposer = diseaseComposer;
        this.chatComposer = chatComposer;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        // 两种请求体都接受：
        //   完整版 {crop, symptomText, leafAge, ...}（对接文档里的契约）
        //   简版   {sessionId, question}（前端对话气泡的调法）
        String question = firstNonBlank(body.get("question"), body.get("message"));
        String context = firstNonBlank(body.get("symptomText"));
        String symptomText = context == null ? question
                : question == null || context.contains(question) ? context : context + "\n补充问题：" + question;
        // Latest utterance has priority over the previous diagnosis attached as context.
        var socialReply = conversation.reply(firstNonBlank(question, context),
                context != null && !context.equals(question));
        String crop = firstNonBlank(body.get("crop"));
        SymptomQuery query = new SymptomQuery(
                crop == null ? "未指明作物" : crop,
                str(body.get("part")),
                str(body.get("leafAge")),
                symptomText == null ? "" : symptomText,
                str(body.get("env")), str(body.get("soil")), str(body.get("stage")),
                str(body.get("mode")),
                str(body.get("sessionId")));

        SseEmitter emitter = new SseEmitter(60_000L);
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long t0 = System.currentTimeMillis();

        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("sessionId", query.sessionId() == null ? "s-" + traceId : query.sessionId());
                meta.put("traceId", traceId);
                meta.put("capabilities", new String[]{"rule", "template"});
                send(emitter, "meta", meta);

                if (socialReply.isPresent()) {
                    sendAnswer(emitter, socialReply.get());
                    send(emitter, "done", Map.of("decision", "CHAT", "costMs", System.currentTimeMillis() - t0));
                    emitter.complete();
                    return;
                }

                var disease = diseases.match(symptomText, crop);
                if (disease.isPresent()) {
                    sendAnswer(emitter, diseaseComposer.compose(disease.get()));
                    send(emitter, "done", Map.of("decision", "DISEASE", "costMs", System.currentTimeMillis() - t0));
                    emitter.complete();
                    return;
                }

                DiagnoseResult r = service.diagnose(query);

                // 先推候选，让用户立刻看到"系统在想什么"
                for (Candidate c : r.candidates()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("element", c.element());
                    m.put("elementName", c.elementName());
                    m.put("confidence", c.confidence());
                    send(emitter, "candidate", m);
                    Thread.sleep(80);
                }

                String topElement = r.candidates().isEmpty() ? null : r.candidates().get(0).element();
                String composed = chatComposer.compose(r.advice(), topElement, question);
                String answer = r.triage() != null ? r.triage().summary() + " " + String.join(" ", r.triage().actions())
                        : composed != null ? composed : chatComposer.fallback(r);

                sendAnswer(emitter, answer);

                if (r.sizheng() != null) {
                    Map<String, Object> sz = new LinkedHashMap<>();
                    sz.put("story", r.sizheng().story());
                    sz.put("value", r.sizheng().value());
                    send(emitter, "sizheng", sz);
                }

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("requestId", r.requestId());
                done.put("decision", r.decision());
                done.put("costMs", System.currentTimeMillis() - t0);
                done.put("degraded", r.degraded());
                send(emitter, "done", done);

                emitter.complete();
            } catch (Exception ex) {
                log.error("流式对话失败", ex);
                try {
                    send(emitter, "error", Map.of("code", 5000, "message", "生成过程异常，已中止"));
                } catch (Exception ignore) {
                    // 流已断开，无需处理
                }
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void sendAnswer(SseEmitter emitter, String answer) throws Exception {
        for (int i = 0; i < answer.length(); i += CHUNK) {
            send(emitter, "token", Map.of("delta", answer.substring(i, Math.min(answer.length(), i + CHUNK))));
            Thread.sleep(CHUNK_DELAY_MS);
        }
    }

    private String firstNonBlank(Object... vals) {
        for (Object v : vals) {
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private void send(SseEmitter emitter, String event, Object data) throws Exception {
        emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
    }
}
