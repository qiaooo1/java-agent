package com.agent4.rag;

import com.agent4.store.SqliteStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库 DAO：负责文档分块的落库与向量检索。
 *
 * <p>向量以逗号分隔的字符串存 SQLite（演示项目不引向量数据库，够用且零运维；
 * 生产可平滑替换为 pgvector / Milvus 等）。检索时把查询也向量化，对每个 chunk
 * 计算余弦相似度，取 Top-K 返回。</p>
 */
public final class KnowledgeDao {

    /** 检索命中结果。 */
    public record Hit(long id, String source, String content, double score) {
    }

    private final SqliteStore store;
    private final Embedding embedding;

    public KnowledgeDao(SqliteStore store, Embedding embedding) {
        this.store = store;
        this.embedding = embedding;
    }

    /** 摄入一份文档：分块 → 向量化 → 逐块落库。 */
    public int ingest(String source, String text, TextChunker chunker) {
        List<String> chunks = chunker.chunk(text);
        return store.withWriteLock(() -> {
            int inserted = 0;
            String sql = """
                    INSERT INTO knowledge_chunk (source, chunk_index, content, vector)
                    VALUES (?,?,?,?)
                    """;
            // 写连接是共享的，不能关闭；只关闭 PreparedStatement，连接由 store 统一管理
            Connection c = store.writeConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < chunks.size(); i++) {
                    String chunk = chunks.get(i);
                    if (chunk.isBlank()) {
                        continue;
                    }
                    ps.setString(1, source);
                    ps.setInt(2, i);
                    ps.setString(3, chunk);
                    ps.setString(4, encode(embedding.embed(chunk)));
                    ps.addBatch();
                    inserted++;
                }
                ps.executeBatch();
                return inserted;
            } catch (SQLException e) {
                throw new IllegalStateException("知识库摄入失败: " + e.getMessage(), e);
            }
        });
    }

    /** 向量检索：返回与 query 最相关的 topK 个 chunk（按相似度降序）。 */
    public List<Hit> search(String query, int topK) {
        double[] qv = embedding.embed(query);
        List<Hit> all = new ArrayList<>();
        String sql = "SELECT id, source, content, vector FROM knowledge_chunk";
        try (Connection c = store.connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                double[] cv = decode(rs.getString("vector"));
                double score = embedding.cosine(qv, cv);
                all.add(new Hit(rs.getLong("id"), rs.getString("source"),
                        rs.getString("content"), score));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("知识库检索失败: " + e.getMessage(), e);
        }
        all.sort((a, b) -> Double.compare(b.score(), a.score()));
        return all.subList(0, Math.min(topK, all.size()));
    }

    /** 统计已摄入的 chunk 数与文档数。 */
    public long chunkCount() {
        String sql = "SELECT COUNT(*) FROM knowledge_chunk";
        try (Connection c = store.connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("统计失败: " + e.getMessage(), e);
        }
    }

    /** 向量 → 逗号分隔字符串。 */
    private static String encode(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }

    /** 逗号分隔字符串 → 向量。 */
    private static double[] decode(String s) {
        String[] parts = s.split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Double.parseDouble(parts[i]);
        }
        return v;
    }
}
