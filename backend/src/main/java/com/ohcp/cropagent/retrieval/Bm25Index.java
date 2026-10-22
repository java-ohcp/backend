package com.ohcp.cropagent.retrieval;

import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BM25 通道：术语精确命中。
 *
 * 解决的是规则矩阵覆盖不到的表达——用户说"脐腐病""小叶病""花而不实"这类专业词，
 * 规则表里可能没有对应特征，但知识条目原文里有，词面命中就能召回。
 *
 * 中文分词用二元切分（bigram）+ 词典词，不引分词库：
 * 千级文档下够用，且零依赖、零模型加载，启动 0 耗时。
 */
@Component
public class Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(Bm25Index.class);

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final KnowledgeRepository kb;

    /** element -> 词频 */
    private record Index(Map<String, Map<String, Integer>> tfs, Map<String, Integer> lengths,
                         Map<String, Integer> df, double avgLen, int docCount) {}
    private volatile Index index = new Index(Map.of(), Map.of(), Map.of(), 1, 0);

    public Bm25Index(KnowledgeRepository kb) {
        this.kb = kb;
    }

    public record Hit(String element, double score) {
    }

    @PostConstruct
    public void build() {
        Map<String, Map<String, Integer>> tfs = new HashMap<>();
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        int docCount = 0;
        int totalLen = 0;
        for (String el : kb.elements()) {
            List<String> tokens = tokenize(kb.evidenceText(el));
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            tfs.put(el, Map.copyOf(tf));
            lengths.put(el, tokens.size());
            totalLen += tokens.size();
            for (String t : tf.keySet()) {
                df.merge(t, 1, Integer::sum);
            }
            docCount++;
        }
        double avgLen = docCount == 0 ? 1 : (double) totalLen / docCount;
        index = new Index(Map.copyOf(tfs), Map.copyOf(lengths), Map.copyOf(df), avgLen, docCount);
        log.info("BM25 索引构建完成：{} 篇文档，词表 {} 个", docCount, df.size());
    }

    public List<Hit> search(String query, int topK) {
        Index current = index;
        var tfs = current.tfs();
        var lengths = current.lengths();
        var df = current.df();
        double avgLen = current.avgLen();
        int docCount = current.docCount();
        List<String> qTokens = tokenize(query);
        if (qTokens.isEmpty() || docCount == 0) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> e : tfs.entrySet()) {
            String el = e.getKey();
            Map<String, Integer> tf = e.getValue();
            double len = lengths.get(el);
            double score = 0;
            for (String q : new LinkedHashSet<>(qTokens)) {
                Integer f = tf.get(q);
                if (f == null) {
                    continue;
                }
                int n = df.getOrDefault(q, 0);
                double idf = Math.log(1 + (docCount - n + 0.5) / (n + 0.5));
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * len / avgLen));
            }
            if (score > 0) {
                hits.add(new Hit(el, score));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }

    /** 二元切分 + 保留单字中文，去掉标点数字。 */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String s = text.replaceAll("[\\p{Punct}\\s，。；：、（）()【】《》~%]", "");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                out.add(String.valueOf(c));
                if (i + 1 < s.length()) {
                    char d = s.charAt(i + 1);
                    if (d >= 0x4E00 && d <= 0x9FFF) {
                        out.add("" + c + d);
                    }
                }
            }
        }
        return out;
    }

    public int size() {
        return index.docCount();
    }

    public Set<String> vocabulary() {
        return index.df().keySet();
    }
}
