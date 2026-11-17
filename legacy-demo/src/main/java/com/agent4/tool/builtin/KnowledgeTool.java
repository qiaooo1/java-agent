package com.agent4.tool.builtin;

import com.agent4.rag.Embedding;
import com.agent4.rag.KnowledgeDao;
import com.agent4.rag.TextChunker;
import com.agent4.tool.Tool;
import com.agent4.tool.ToolParam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库（RAG）工具：让 Agent 能「检索知识」再回答。
 *
 * <p>这是 RAG 与 ReAct 循环结合的落地方式——Agent 遇到需要「内部知识/特定文档」
 * 才能回答的问题时，先调用 {@code search_knowledge} 检索知识库，把命中的片段
 * 作为依据再作答，而不是凭空编造。配合 {@code ingest_document} 可动态把文档
 * 喂进知识库。</p>
 *
 * <p>典型用法：把某门课资料、项目接口文档、企业规章制度等喂进去，用户用自然
 * 语言提问，Agent 自动检索相关片段并基于原文回答（可要求它标注来源）。</p>
 */
public class KnowledgeTool {

    private final KnowledgeDao knowledgeDao;
    private final TextChunker chunker;

    public KnowledgeTool(KnowledgeDao knowledgeDao, TextChunker chunker) {
        this.knowledgeDao = knowledgeDao;
        this.chunker = chunker;
    }

    @Tool(name = "ingest_document", description = "把一段文档文本摄入知识库（自动分块+向量化），后续可被 search_knowledge 检索。source 是文档名，content 是文档正文")
    public String ingestDocument(
            @ToolParam("文档名称，如「苍穹外卖接口文档」") String source,
            @ToolParam("文档正文内容") String content) {
        int n = knowledgeDao.ingest(source, content, chunker);
        return "已摄入文档「" + source + "」，共切分并向量化 " + n + " 个片段";
    }

    @Tool(name = "search_knowledge", description = "在知识库中检索与问题最相关的片段（向量相似度 Top-K），返回内容与来源。回答需要引用内部知识时优先调用")
    public String searchKnowledge(
            @ToolParam("检索的问题或关键词") String query,
            @ToolParam(value = "返回片段数，默认 3", required = false) Integer topK) {
        int k = topK == null ? 3 : Math.min(Math.max(topK, 1), 10);
        List<KnowledgeDao.Hit> hits = knowledgeDao.search(query, k);
        if (hits.isEmpty()) {
            return "知识库为空，未检索到相关内容。可用 ingest_document 先摄入文档";
        }
        return hits.stream()
                .map(h -> "【来源：" + h.source() + "｜相似度 "
                        + String.format("%.3f", h.score()) + "】\n" + h.content())
                .collect(Collectors.joining("\n\n"));
    }
}
