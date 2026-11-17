package com.agent4.worker; // 工具实例只能访问当前任务授权的文档。
import com.agent4.backend.KnowledgeStore; // 调用带用户过滤的知识检索。
import com.agent4.tool.*; // 使用已有注解注册协议。
import java.util.stream.Collectors; // 将命中片段转换为模型可引用的文本。
public class TaskKnowledgeTool { // 无写操作，允许租约故障后重试。
    private final KnowledgeStore knowledge; private final String user, document; // 不接受模型传入任意用户或文档标识。
    public TaskKnowledgeTool(KnowledgeStore knowledge, String user, String document) { this.knowledge = knowledge; this.user = user; this.document = document; } // 绑定任务知识范围。
    @Tool(name = "search_knowledge", description = "检索当前任务已授权文档的相关片段，回答须标注来源；没有命中时明确说明依据不足") // 工具权限通过实例能力约束，不靠提示词约束。
    public String search(@ToolParam("要检索的问题或关键词") String query) { // 模型只能提交检索文本。
        var hits = knowledge.search(user, document, query, 3); // 最多三个片段，限制上下文开销。
        return hits.isEmpty() ? "未检索到足够相关的片段，请说明依据不足" : hits.stream().map(hit -> "【文档 " + document + "，相似度 " + String.format(java.util.Locale.ROOT, "%.3f", hit.score()) + "】\n" + hit.content()).collect(Collectors.joining("\n\n")); // 不把无关片段强行作为事实依据。
    } // 结束授权检索。
} // 结束任务知识工具。
