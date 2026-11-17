package com.agent4.backend; // 知识查询始终携带用户和文档范围。
import com.agent4.rag.*; // 复用原项目轻量分块与特征哈希算法。
import com.fasterxml.jackson.databind.ObjectMapper; // 向量存储为明确 JSON，后续替换模型需重建。
import org.springframework.jdbc.core.JdbcTemplate; // 使用预编译参数约束文档范围。
import org.springframework.transaction.PlatformTransactionManager; // 文档与片段必须一并落库。
import org.springframework.transaction.support.TransactionTemplate; // 不依赖同类代理调用。
import org.springframework.stereotype.Service; // 平台负责摄入，Worker 负责受限检索。
import java.util.*; // 文档标识、排序与响应。
@Service // 第一版仅面向小规模文本知识库。
public class KnowledgeStore { // 明确限制单文档规模，避免伪装成大规模向量检索系统。
    private final JdbcTemplate db; private final TransactionTemplate tx; private final ObjectMapper json; // 保存数据库与序列化依赖。
    private final Embedding embedding = new Embedding(256); private final TextChunker chunker = new TextChunker(600, 60); // 固定参数保证入库和检索向量一致。
    public record Hit(String content, double score) {} // 对外检索结果仅保留片段与相似度。
    public KnowledgeStore(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json) { this.db = db; this.tx = new TransactionTemplate(manager); this.json = json; } // 使用构造器方便离线测试。
    public String ingest(String user, String title, String content) { // 用户只能给自己的知识空间摄入文本。
        if (title == null || title.isBlank() || title.length() > 120 || content == null || content.isBlank() || content.length() > 100000) throw new BusinessException(400, "文档标题或正文长度无效，正文最多十万字符"); // 入库前限制规模。
        List<String> chunks = chunker.chunk(content); // CPU 分块在事务外完成。
        List<String> vectors = chunks.stream().map(chunk -> encode(embedding.embed(chunk))).toList(); // 向量计算不占用数据库行锁。
        String id = UUID.randomUUID().toString(); // 标识由服务端生成。
        tx.executeWithoutResult(status -> { // 片段失败时整篇文档回滚。
            db.update("INSERT INTO knowledge_document(id,user_id,title) VALUES(?,?,?)", id, user, title); // 保存文档归属。
            for (int i = 0; i < chunks.size(); i++) db.update("INSERT INTO knowledge_chunk(document_id,chunk_no,content,vector_json) VALUES(?,?,?,?)", id, i, chunks.get(i), vectors.get(i)); // 固定上限下使用明确插入，批量优化留给测量验证。
        }); // 完成文档事务。
        return id; // 后续任务必须显式选择这个文档。
    } // 结束知识摄入。
    public List<Map<String, Object>> list(String user) { return db.queryForList("SELECT id,title,created_at FROM knowledge_document WHERE user_id=? ORDER BY created_at DESC LIMIT 100", user); } // 列表不泄露其他用户文档。
    public List<Hit> search(String user, String document, String query, int limit) { // 任务中的知识工具绑定到授权文档。
        if (db.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE id=? AND user_id=?", Integer.class, document, user) == 0) throw new BusinessException(404, "知识文档不存在"); // Worker 也必须校验归属。
        double[] vector = embedding.embed(query); // 为查询计算相同算法的向量。
        List<Hit> hits = db.query("SELECT content,vector_json FROM knowledge_chunk WHERE document_id=?", (r, n) -> new Hit(r.getString("content"), embedding.cosine(vector, decode(r.getString("vector_json")))), document); // 仅扫描当前文档，不扫描全平台知识。
        return hits.stream().filter(hit -> hit.score() >= 0.15).sorted(Comparator.comparingDouble(Hit::score).reversed()).limit(Math.max(1, Math.min(limit, 5))).toList(); // 阈值是可调整的启发式，需要任务集评估，不能宣称语义模型效果。
    } // 结束受限检索。
    private String encode(double[] vector) { try { return json.writeValueAsString(vector); } catch (Exception e) { throw new IllegalStateException("向量编码失败", e); } } // 编码失败阻止不完整文档提交。
    private double[] decode(String vector) { try { return json.readValue(vector, double[].class); } catch (Exception e) { throw new IllegalStateException("向量解码失败", e); } } // 损坏向量不能静默返回错误结果。
} // 结束知识存储。
