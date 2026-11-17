package com.agent4.config;

import com.agent4.agent.AgentConfig;
import com.agent4.agent.ReActAgent;
import com.agent4.llm.LLMClient;
import com.agent4.llm.MockLLMClient;
import com.agent4.llm.OpenAiCompatClient;
import com.agent4.quota.QuotaService;
import com.agent4.rag.Embedding;
import com.agent4.rag.KnowledgeDao;
import com.agent4.rag.TextChunker;
import com.agent4.store.NoteDao;
import com.agent4.store.SqliteStore;
import com.agent4.store.TaskHistoryDao;
import com.agent4.tool.ToolRegistry;
import com.agent4.tool.builtin.BasicTools;
import com.agent4.tool.builtin.HttpFetchTool;
import com.agent4.tool.builtin.KnowledgeTool;
import com.agent4.tool.builtin.NoteTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.util.List;

/**
 * Spring IoC 装配：把 Agent 各组件用构造器注入组装起来。
 */
@Configuration
public class WorkbenchConfiguration {

    @Bean
    public SqliteStore sqliteStore(@Value("${storage.db-path:data/workbench.db}") String dbPath) {
        // 相对路径解析到项目根目录
        java.nio.file.Path p = Paths.get(dbPath);
        return new SqliteStore(p.toString());
    }

    @Bean
    public TaskHistoryDao taskHistoryDao(SqliteStore store) {
        return new TaskHistoryDao(store);
    }

    @Bean
    public NoteDao noteDao(SqliteStore store) {
        return new NoteDao(store);
    }

    @Bean
    public QuotaService quotaService(TaskHistoryDao dao,
                                     @Value("${quota.monthly-token-quota:1000000}") int quota) {
        return new QuotaService(dao, quota);
    }

    @Bean
    public Embedding embedding(@Value("${rag.embedding-dim:256}") int dim) {
        return new Embedding(dim);
    }

    @Bean
    public TextChunker textChunker(
            @Value("${rag.chunk-size:600}") int chunkSize,
            @Value("${rag.chunk-overlap:60}") int overlap) {
        return new TextChunker(chunkSize, overlap);
    }

    @Bean
    public KnowledgeDao knowledgeDao(SqliteStore store, Embedding embedding) {
        return new KnowledgeDao(store, embedding);
    }

    @Bean
    public ToolRegistry toolRegistry(
            @Value("${agent.tool-timeout-ms:30000}") long timeoutMs,
            NoteDao noteDao,
            KnowledgeDao knowledgeDao,
            TextChunker textChunker) {
        return new ToolRegistry(timeoutMs)
                .register(new BasicTools())
                .register(new HttpFetchTool())
                .register(new NoteTool(noteDao))
                .register(new KnowledgeTool(knowledgeDao, textChunker));
    }

    @Bean
    public LLMClient llmClient(
            @Value("${agent.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agent.api-key:}") String apiKey,
            @Value("${agent.model:deepseek-chat}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            // 未配置 key：用 Mock 客户端，保证工作台能跑通全流程（演示/测试用）
            return new MockLLMClient(List.of(
                    new com.agent4.llm.ChatResponse(
                            "（未配置 DEEPSEEK_API_KEY，当前为演示模式）", List.of(),
                            new com.agent4.llm.ChatResponse.Usage(0, 0))));
        }
        return new OpenAiCompatClient(baseUrl, apiKey, model);
    }

    @Bean
    public AgentConfig agentConfig(
            @Value("${agent.model:deepseek-chat}") String model,
            @Value("${agent.temperature:0.3}") double temperature,
            @Value("${agent.max-tokens:2048}") int maxTokens,
            @Value("${agent.max-iterations:10}") int maxIterations,
            @Value("${agent.tool-timeout-ms:30000}") long toolTimeoutMs,
            @Value("${agent.memory-window-messages:40}") int memoryWindowMessages,
            @Value("${agent.memory-window-chars:60000}") int memoryWindowChars,
            @Value("${agent.consecutive-failure-limit:3}") int consecutiveFailureLimit,
            @Value("${agent.stream:true}") boolean stream) {
        return AgentConfig.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .maxIterations(maxIterations)
                .toolTimeoutMs(toolTimeoutMs)
                .memoryWindowMessages(memoryWindowMessages)
                .memoryWindowChars(memoryWindowChars)
                .consecutiveFailureLimit(consecutiveFailureLimit)
                .stream(stream)
                .build();
    }

    @Bean
    public ReActAgent reActAgent(LLMClient llm, ToolRegistry tools, AgentConfig config) {
        return new ReActAgent(llm, tools, config);
    }
}
