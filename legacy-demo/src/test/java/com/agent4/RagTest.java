package com.agent4;

import com.agent4.rag.Embedding;
import com.agent4.rag.KnowledgeDao;
import com.agent4.rag.TextChunker;
import com.agent4.store.SqliteStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 全链路测试：分块 → 向量化 → 摄入落库 → 相似度检索。
 */
class RagTest {

    @Test
    void 分块器按段落切分且不超上限() {
        TextChunker chunker = new TextChunker(50, 10);
        String text = "第一段内容，讲的是苍穹外卖的菜品接口。\n\n" +
                "第二段内容，讲的是订单接口的分页查询，参数包含 page 和 pageSize。";
        List<String> chunks = chunker.chunk(text);
        assertTrue(chunks.size() >= 2, "至少切成两个块");
        for (String c : chunks) {
            assertTrue(c.length() <= 50 + 10, "单块长度不能超过上限（含重叠）");
        }
    }

    @Test
    void 向量化相同文本相似度高() {
        Embedding emb = new Embedding(256);
        double[] a = emb.embed("苍穹外卖菜品分页查询接口");
        double[] b = emb.embed("苍穹外卖的菜品分页查询接口怎么用");
        double[] c = emb.embed("今天天气很好我要去公园散步");
        double simAB = emb.cosine(a, b);
        double simAC = emb.cosine(a, c);
        assertTrue(simAB > simAC, "相关文本相似度应高于无关文本");
    }

    @Test
    void 摄入后检索能召回相关片段() throws Exception {
        Path db = Files.createTempFile("rag", ".db");
        SqliteStore store = new SqliteStore(db.toString());
        Embedding emb = new Embedding(256);
        KnowledgeDao dao = new KnowledgeDao(store, emb);
        TextChunker chunker = new TextChunker(200, 20);

        String doc = "苍穹外卖菜品分页查询接口：GET /api/dish/page，参数 page、pageSize、" +
                "name、categoryId、status，返回 records、total、pages。";
        int n = dao.ingest("菜品接口文档", doc, chunker);
        assertTrue(n >= 1, "至少摄入一个 chunk");

        List<KnowledgeDao.Hit> hits = dao.search("菜品怎么分页查询", 3);
        assertTrue(!hits.isEmpty(), "应能检索到结果");
        assertTrue(hits.get(0).content().contains("分页查询") || hits.get(0).content().contains("dish/page"),
                "最相关片段应包含查询关键词");

        assertEquals(n, dao.chunkCount(), "chunk 统计应一致");
        store.close();
    }
}
