package com.ohcp.cropagent.agent;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Lightweight social intents; agricultural requests stay in the evidence-based pipeline. */
@Component
public class HeheConversation {
    private record Reply(String pattern, String text) {}
    private static final List<Reply> REPLIES = List.of(
        new Reply("(?:禾禾)?(?:你好|您好|嗨|哈喽|hello|hi|早上好|早安|下午好|晚上好|在吗|在不在|嘿|你好呀|你好啊|你好禾禾|禾禾你好)[呀啊呢吗哦~]*",
            "你好呀，我是禾禾！很高兴见到你。想随便聊两句，还是一起看看作物的情况？"),
        new Reply("(?:禾禾|你是谁|你叫什么(?:名字)?|介绍一下你自己|自我介绍|你是机器人吗|你是ai吗)",
            "我是禾禾，你的作物观察小助手。你可以和我打招呼，也可以告诉我作物名称、叶片变化和田间情况，我们一起整理线索。"),
        new Reply("(?:你能做什么|你会什么|怎么用|帮助|使用帮助|有什么功能|你能帮我什么|我该怎么问)[呀啊呢吗]*",
            "你可以直接和我聊天。分析作物时，试着这样问：‘番茄叶片出现水渍状病斑，叶背有白色霉层，是什么情况？’也可以补充发生部位、最近天气和管理情况。页面照片目前只做本地预览，还不会自动识图。"),
        new Reply("(?:谢谢(?:你|禾禾)?|感谢(?:你|禾禾)?|多谢|谢啦|辛苦了|谢谢啦)[呀啊呢哦]*",
            "不客气呀！能帮到你就好。有新情况，随时来找禾禾。"),
        new Reply("(?:再见|拜拜|下次见|回头见|bye|goodbye)[呀啊啦]*",
            "拜拜，期待下次见面！有新的观察，再来和禾禾聊聊。"),
        new Reply("(?:晚安|我要睡了|睡觉了)[呀啊啦]*", "晚安呀，今天辛苦了。好好休息，我们下次再聊！"),
        new Reply("(?:你真棒|你好棒|你真厉害|真不错|太棒了|好厉害|棒棒哒)[呀啊呢]*",
            "谢谢你的鼓励！禾禾会认真帮你整理线索，遇到不确定的地方也会说明。"),
        new Reply("(?:对不起|抱歉|不好意思)[呀啊啦]*", "没关系呀，我们接着聊就好。"),
        new Reply("(?:你好吗|你今天怎么样|最近怎么样)[呀啊呢]*", "我在这里，随时准备听你说。你今天过得怎么样？"),
        new Reply("(?:我)?(?:心情不好|不开心|有点难过|好累|很累|有点累|压力好大|压力很大)[呀啊了]*",
            "听起来今天有些不容易。愿意的话，可以和禾禾说说发生了什么，也可以先休息一会儿。"),
        new Reply("(?:讲个笑话|讲一个笑话|说个笑话|逗我开心)[吧呀啊]*",
            "讲个田间小笑话：小苗问园丁，‘我什么时候才能长大？’园丁说，‘先别急着出头，把根扎稳！’希望让你轻松一点。"),
        new Reply("(?:加油|鼓励我一下|给我打气|给我一点鼓励)[吧呀啊]*",
            "慢慢来，把眼前的一小步做好就很不错了。禾禾给你加油！"),
        new Reply("(?:好的|好呀|好啊|好哒|嗯嗯|嗯|明白了|知道了|收到|ok|okay)[呀啊呢]*",
            "好呀，我在。想继续聊什么，直接告诉禾禾就可以。")
    );
    private static final List<String> AGRICULTURAL = List.of(
        "叶", "根", "茎", "果", "花", "苗", "病", "虫", "肥", "土", "水", "光照", "湿度", "温度", "种植",
        "作物", "番茄", "西红柿", "水稻", "小麦", "玉米", "黄瓜", "辣椒", "白菜", "马铃薯", "缺素", "农药", "枯", "斑", "腐", "霉"
    );

    public Optional<String> reply(String message) {
        if (message == null || message.isBlank()) return Optional.empty();
        String normalized = message.strip().toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]", "");
        for (Reply reply : REPLIES) {
            if (normalized.matches(reply.pattern())) return Optional.of(reply.text());
        }
        // A greeting followed by symptoms must not swallow the diagnostic question.
        if (AGRICULTURAL.stream().anyMatch(normalized::contains)) return Optional.empty();
        return Optional.of("禾禾听到了，不过我还没理解你具体想问什么。可以换个说法，也可以问我‘你能做什么’。如果是作物问题，请告诉我作物名称和观察到的变化。");
    }
}
