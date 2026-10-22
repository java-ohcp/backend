package com.ohcp.cropagent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class HeheConversationTest {
    private final HeheConversation conversation = new HeheConversation();

    @ParameterizedTest
    @ValueSource(strings = {"你好啊", "禾禾你好！", "HI", "你是谁", "你能做什么", "谢谢禾禾", "拜拜", "晚安", "我好累", "讲个笑话吧", "给我打气", "好的", "对不起", "你真棒", "你好吗"})
    void handlesEverydayMessagesWithoutDiagnosis(String text) {
        var reply = conversation.reply(text).orElseThrow();
        assertFalse(reply.contains("发黄是从"));
        assertFalse(reply.isBlank());
    }
    @ParameterizedTest
    @ValueSource(strings = {"你好，番茄叶片发黄怎么办", "谢谢，但是叶背还有白霉", "小麦根部腐烂", "番茄晚疫病怎么排查", "土壤湿度很高", "叶子出现病斑"})
    void preservesAgriculturalRequests(String text) {
        assertTrue(conversation.reply(text).isEmpty());
    }
    @Test void unsupportedSmallTalkAsksForClarificationRatherThanNutrientSymptoms() {
        assertTrue(conversation.reply("你最喜欢什么音乐").orElseThrow().contains("换个说法"));
        assertTrue(conversation.reply("你能做什么").orElseThrow().contains("不会自动识图"));
        assertTrue(conversation.reply(null).isEmpty());
    }
    @Test void shortFollowUpsUseDiagnosisContextWhileGreetingsRemainSocial() {
        assertTrue(conversation.reply("为什么", true).isEmpty());
        assertTrue(conversation.reply("什么时候", true).isEmpty());
        assertTrue(conversation.reply("你好啊", true).orElseThrow().contains("我是禾禾"));
    }
}
