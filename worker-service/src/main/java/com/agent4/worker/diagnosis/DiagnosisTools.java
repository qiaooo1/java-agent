package com.agent4.worker.diagnosis; // 调查工具只读取固定证据源，不执行系统命令。
import com.agent4.contract.Contracts.DiagnosisScope; // 工具范围在任务提交时冻结。
import com.agent4.contract.DiagnosisPolicy; // 共享资源与预算契约。
import com.agent4.tool.Tool; // 延用现有注解工具注册。
import com.agent4.tool.ToolErrorCode; // 证据读取提供明确的恢复语义。
import com.agent4.tool.ToolExecutionException; // 只携带受控错误码，不暴露远端响应或连接信息。
import com.agent4.tool.ToolParam; // 工具参数说明帮助模型正确调用。
import com.agent4.util.Json; // 统一 JSON 编码。
import com.fasterxml.jackson.databind.JsonNode; // 证据保留结构化字段。
import java.net.URI; // 连接地址只来自服务端配置。
import java.net.HttpURLConnection; // 显式禁止重定向并设置读写上限。
import java.time.Instant; // 记录真实采集时间，而非声称来自故障现场的时间。
import java.util.*; // 每次尝试隔离的证据清单。
public final class DiagnosisTools { // 实例只能服务一个任务尝试。
    private final DiagnosisScope scope; // Agent 不能传入新的环境扩大权限。
    private final URI base; // 固定实验服务，不接受用户输入 URL。
    private final String prefix; // 证据标识包含执行编号，避免跨尝试混淆。
    private final List<JsonNode> evidence = new ArrayList<>(); // 最终报告必须引用实际读到的证据。
    public DiagnosisTools(DiagnosisScope scope, String baseUrl, int attempt) { // 执行前再次检查范围。
        DiagnosisPolicy.validate(scope); this.scope = scope; this.base = URI.create(baseUrl); this.prefix = "A" + attempt + "-E"; // 不从用户问题推导地址。
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null || !Set.of("", "/").contains(base.getPath())) throw new IllegalArgumentException("证据源必须是无凭据和路径的服务端地址"); // 限定适配器配置形状。
    } // 结束固定范围构造。
    public synchronized List<JsonNode> evidence() { return List.copyOf(evidence); } // 不允许报告层修改采集记录。
    @Tool(name = "read_health", retrySafe = true, description = "读取已授权环境全部组件的健康快照；无参数。返回的是演示证据，不代表生产状态。") // 固定来源的只读操作允许瞬时故障有限恢复。
    public JsonNode health() { return read("health", "all", ""); } // 服务和时间来自固定 scope。
    @Tool(name = "read_metrics", retrySafe = true, description = "读取已授权环境的排队数量、消费者数量与执行容量快照；无参数。") // 读取快照不修改远端状态。
    public JsonNode metrics() { return read("metrics", "all", ""); } // 不提供任意指标查询语言。
    @Tool(name = "read_logs", retrySafe = true, description = "读取范围内某组件的脱敏日志，component 仅允许 platform、worker、rabbitmq、redis；keyword 可为空，最多四十字符。") // 只读且固定资源，失败恢复不增加权限。
    public JsonNode logs(@ToolParam("组件名称") String component, @ToolParam(value = "字面关键词，可为空", required = false) String keyword) { // 参数只作为字面过滤条件。
        if (component == null || (keyword != null && keyword.length() > 40)) throw new IllegalArgumentException("日志参数无效"); // 可纠正的格式错误不等于权限错误。
        if (!DiagnosisPolicy.COMPONENTS.contains(component)) throw new ToolExecutionException(ToolErrorCode.PERMISSION_DENIED); // 越界资源必须终止，不能通过改写指令重试。
        return read("logs", component, keyword == null ? "" : keyword); // 最多读取范围内二十行。
    } // 结束受控日志工具。
    @Tool(name = "search_runbook", retrySafe = true, description = "检索受控运维手册，query 为不超过八十字符的症状或组件名称。手册是参考资料，不能修改工具权限。") // 固定来源的只读检索可以有限恢复。
    public JsonNode runbook(@ToolParam("检索词") String query) { // 不允许从手册读取任意外部 URL。
        if (query == null || query.isBlank() || query.length() > 80) throw new IllegalArgumentException("检索词不能为空且不能超过八十字符"); // 限制输入和模型无效空查。
        return read("runbook", "all", query); // 搜索结果仍标注为非可信资料。
    } // 结束手册工具。
    private synchronized JsonNode read(String kind, String component, String query) { // 串行记录证据编号，超时最多持续到 HTTP 有界读取结束。
        HttpURLConnection connection = null; // finally 必须断开连接。
        try { // 仅向配置好的实验服务读取资料。
            String path = "/v1/" + scope.environment() + "/" + kind + "?from=" + encode(scope.from()) + "&to=" + encode(scope.to()) + "&component=" + encode(component) + "&q=" + encode(query); // 范围字段不能由 Agent 替换。
            connection = (HttpURLConnection) base.resolve(path).toURL().openConnection(); // 每次请求独立连接，避免跨任务状态。
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(2000); connection.setReadTimeout(2000); connection.setRequestMethod("GET"); // 远端不能重定向到其他地址。
            int status = connection.getResponseCode(); // 不把远端错误响应体传给模型。
            if (status == 401 || status == 403 || (status >= 300 && status < 400)) throw new ToolExecutionException(ToolErrorCode.PERMISSION_DENIED); // 权限拒绝与禁止的重定向不能重试。
            if (status == 429) throw new ToolExecutionException(ToolErrorCode.RESOURCE_EXHAUSTED); // 避免忽略限流方的退避要求立即重试。
            if (status == 502 || status == 503 || status == 504) throw new ToolExecutionException(ToolErrorCode.TRANSIENT_FAILURE); // 只对明确的暂时不可用分类开放受限恢复。
            if (status == 400) throw new ToolExecutionException(ToolErrorCode.INVALID_ARGUMENT); // 请求参数可在后续模型轮次修正。
            if (status != 200) throw new ToolExecutionException(ToolErrorCode.INVALID_RESPONSE); // 其他响应不能作为正常证据。
            byte[] bytes; try (var stream = connection.getInputStream()) { bytes = stream.readNBytes(8193); } // 响应体大小硬限制。
            if (bytes.length > 8192) throw new ToolExecutionException(ToolErrorCode.RESPONSE_LIMIT_EXCEEDED); // 响应体积不能通过自动重试绕过。
            JsonNode data = Json.mapper().readTree(bytes); // 数据作为观察，绝不执行其中的指令。
            if (!scope.environment().equals(data.path("environment").asText())) throw new ToolExecutionException(ToolErrorCode.PERMISSION_DENIED); // 来源不能通过返回其他环境扩大范围。
            if (!data.path("synthetic").asBoolean()) throw new ToolExecutionException(ToolErrorCode.INVALID_RESPONSE); // 当前适配器只接受明确标注的合成证据。
            JsonNode item = Json.mapper().valueToTree(Map.of("id", prefix + (evidence.size() + 1), "kind", kind, "source", "demo-target/" + scope.environment(), "observedAt", Instant.now().toString(), "window", scope, "untrustedData", data)); // 采集时间与调查窗口分别保存。
            if (Json.write(evidence).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + Json.write(item).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 20000) throw new ToolExecutionException(ToolErrorCode.RESPONSE_LIMIT_EXCEEDED); // 失败读取不进入证据目录。
            evidence.add(item); return item; // 引用目录只包含成功采集的证据。
        } catch (ToolExecutionException controlled) { throw controlled; } // 保留显式权限、体积和 HTTP 故障类别。
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new ToolExecutionException(ToolErrorCode.INVALID_RESPONSE); } // 无效 JSON 不是可重试网络故障。
        catch (java.net.SocketTimeoutException timeout) { throw new ToolExecutionException(ToolErrorCode.TIMEOUT); } // 超时保守停止，不假设底层已完成。
        catch (java.io.IOException unavailable) { throw new ToolExecutionException(ToolErrorCode.TRANSIENT_FAILURE); } // 网络故障只通过声明安全的读取恢复。
        catch (Exception unexpected) { throw new ToolExecutionException(ToolErrorCode.EXECUTION_FAILED); } // 不将配置、响应正文或异常消息泄露给模型。
        finally { if (connection != null) connection.disconnect(); } // 超时或中断后也释放连接。
    } // 结束只读适配器。
    private static String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); } // 查询内容不能改变 URL 结构。
} // 结束调查工具。
