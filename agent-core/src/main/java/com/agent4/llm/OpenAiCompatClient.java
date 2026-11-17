package com.agent4.llm; // 兼容客户端集中处理 HTTP 与 SSE 细节，上层只依赖 LLMClient 抽象。

import com.agent4.util.Json; // 复用项目统一 JSON 工具，保证解析与全局配置一致。
import com.fasterxml.jackson.databind.JsonNode; // 用 JsonNode 宽松取值，供应商多返回或缺失字段时不会抛异常。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造请求体需要数组节点，显式导入避免全限定名噪音。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造请求体需要对象节点，按需 put 可省掉大量空值参数。

import java.io.BufferedReader; // 行级读取 SSE，按行切分才能可靠处理 data: 分片边界。
import java.io.InputStreamReader; // 指定 UTF-8 解码，避免中文增量在平台默认编码下乱码。
import java.io.OutputStream; // 请求体经输出流写出，HttpURLConnection 必须显式开启 doOutput。
import java.net.HttpURLConnection; // 使用 JDK 内置连接，项目无需引入额外 HTTP 客户端依赖。
import java.net.URI; // 先 URI 解析再转 URL，避免已废弃的 URL 构造器直接解析字符串。
import java.nio.charset.StandardCharsets; // 所有读写统一显式 UTF-8，跨平台行为一致。
import java.util.ArrayList; // 工具调用按 index 随机插入，需要可变列表承载分片聚合。
import java.util.List; // 聚合结果以只读视角交给上层，接口签名固定为 List。

/**
 * OpenAI 兼容的 LLM 客户端（DeepSeek / GLM / Qwen / Ollama 均可接入）。
 *
 * <p>用 JDK 原生 HttpURLConnection 实现（零第三方 HTTP 依赖），支持 SSE
 * 流式解析：服务端以 {@code data: {...}} 分片推送，本类逐行读取并按
 * {@code data:} 前缀分割，累积 text 增量与 tool_calls 分片，最后聚合返回。</p>
 */
public final class OpenAiCompatClient implements LLMClient { // final 类保证连接生命周期与取消语义不被子类改写。

    private final String baseUrl; // 基地址可配置，以适配 DeepSeek/GLM/Qwen/Ollama 等兼容端点。
    private final String apiKey; // Key 仅保存在实例中，不写入日志或任务快照。
    private final String model; // 模型名构造时固定，单次执行期间不会切换模型。
    private volatile HttpURLConnection activeConnection; // Worker 每次尝试独立创建客户端，便于关闭当前网络调用。
    private volatile boolean cancelled; // 取消标记阻止该执行尝试继续发起新连接。

    public OpenAiCompatClient(String baseUrl, String apiKey, String model) { // 构造不发起网络请求，便于在装配或事务阶段安全创建。
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl; // 未配置地址时回落到官方兼容端点，避免空串拼出非法 URL。
        this.apiKey = apiKey; // 允许空 Key 以支持本地 Ollama 等免鉴权服务。
        this.model = model; // 保存模型名，请求时统一使用而不采用调用方传入的 model。
    } // 结束构造，实例创建阶段不产生副作用。

    @Override // 覆盖流式接口，按请求开关决定实际传输模式。
    public ChatResponse chatStream(ChatRequest request, StreamHandler handler) { // 两个分支都返回聚合结果，调用方无需关心是否流式。
        if (request.stream()) { // 流式标记由 ChatRequest 决定，客户端不自行猜测。
            return doStream(request, handler); // 流式分支逐分片解析，回调随读取即时触发。
        } // 结束流式分支，保证同一次调用只走一条路径。
        return doSync(request, handler); // 非流式走整体 JSON 解析，避免无谓的 SSE 开销。
    } // 结束 chatStream 分派。

    @Override // 覆盖同步接口。
    public ChatResponse chat(ChatRequest request) { // 同步入口可能收到 stream=true 的请求，需要显式降级。
        ChatRequest sync = new ChatRequest(request.model(), request.messages(), request.tools(), // 复制请求字段并重建对象，避免改动调用方持有的不可变请求。
                request.temperature(), request.maxTokens(), false); // 续行把 stream 强制为 false，其余参数原样保留。
        return doSync(sync, null); // 传空回调，同步模式没有增量展示需求。
    } // 结束同步入口。

    /** 非流式请求：一次性读完整个响应 JSON。 */
    private ChatResponse doSync(ChatRequest request, StreamHandler handler) { // 非流式只做一次 HTTP 往返，逻辑保持线性。
        String body = buildBody(request, false); // 请求体按非流式构造，不同时携带 stream_options。
        String resp = httpPost(baseUrl + "/chat/completions", body); // 复用统一 POST 方法，鉴权与超时策略集中在一处。
        return parseFullResponse(resp, handler); // 响应整体解析，工具调用与用量一次取齐。
    } // 结束 doSync。

    /** 流式请求：逐行解析 SSE。 */
    private ChatResponse doStream(ChatRequest request, StreamHandler handler) { // 流式自行建连并逐行读取，不能复用一次性 POST。
        String body = buildBody(request, true); // 请求体开启 stream，并附带用量选项。
        try { // 网络与解析异常统一在外层转换成运行时异常。
            HttpURLConnection conn = open(baseUrl + "/chat/completions"); // 打开连接时已处理取消竞争，之后才可安全写请求体。
            conn.setDoOutput(true); // 需要写出 POST 体，必须开启输出。
            conn.setRequestProperty("Content-Type", "application/json"); // 流式响应仍是 JSON，Content-Type 必须显式声明。
            try (OutputStream os = conn.getOutputStream()) { // try-with-resources 保证请求体流关闭，避免连接挂起。
                os.write(body.getBytes(StandardCharsets.UTF_8)); // UTF-8 写出请求体，中文提示词不会被平台默认编码破坏。
            } // 结束请求体输出块，关闭流后才读取响应。

            int code = conn.getResponseCode(); // 读取状态码才真正触发网络往返。
            if (code != 200) { // 非 200 一律视为失败，不把错误页当模型输出解析。
                throw new IllegalStateException("HTTP " + code + ": " + readBody(conn.getErrorStream())); // 带回错误响应体，便于定位鉴权或参数问题。
            } // 结束状态码检查。

            StringBuilder content = new StringBuilder(); // 累积完整文本，回调只拿增量，聚合结果另行返回。
            List<ToolCall> toolCalls = new ArrayList<>(); // 工具调用按 index 聚合，空列表表示本轮无工具调用。
            int promptTokens = 0; // 用量默认 0，供应商不返回时结算按 0 处理。
            int completionTokens = 0; // 输出 token 默认 0，避免结算层出现 null 空指针。

            try (BufferedReader br = new BufferedReader( // 逐行读取响应体，SSE 以空行分隔事件。
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) { // 响应流固定 UTF-8，避免增量文本乱码。
                String line; // 保存当前行以判断 data: 前缀与结束标记。
                while ((line = br.readLine()) != null) { // 读到流末尾即结束，不依赖服务端主动断开。
                    if (line.isBlank() || !line.startsWith("data:")) { // 空行是事件分隔符，非 data 行如 event/id 直接忽略。
                        continue; // 跳过心跳或注释行，保证协议扩展不破坏解析。
                    } // 结束非数据行过滤。
                    String data = line.substring(5).trim(); // 去掉 data: 前缀后的内容才是 JSON，可能带前导空格。
                    if ("[DONE]".equals(data)) { // [DONE] 表示本次生成结束，此后不再推送用量。
                        break; // 提前跳出循环，避免阻塞等待连接关闭。
                    } // 结束完成标记判断。
                    JsonNode chunk = Json.parse(data); // 单行 data 必须是完整 JSON，解析失败交给外层异常包装。
                    // 文本增量
                    JsonNode choices = chunk.path("choices"); // choices 可能为空数组，仅含 usage 的 chunk 需要判空。
                    if (choices.isArray() && choices.size() > 0) { // 有候选才解析增量，防御供应商返回空数组。
                        JsonNode delta = choices.get(0).path("delta"); // 只取第一个候选，Agent 场景不使用多候选。
                        String text = delta.path("content").asText(""); // content 缺省为空串，工具调用分片通常没有文本。
                        if (!text.isEmpty()) { // 空文本不回调，避免上层收到无意义的增量事件。
                            content.append(text); // 累积全文用于最终响应，回调值只用于实时展示。
                            if (handler != null) { // handler 允许为空，同步或后台调用不展示增量。
                                handler.onContentDelta(text); // 及时回调增量，前端可边生成边渲染。
                            } // 结束回调判断。
                        } // 结束文本增量处理。
                        // 工具调用分片累积
                        JsonNode tc = delta.path("tool_calls"); // 工具调用以分片推送，需要跨 chunk 累积。
                        if (tc.isArray()) { // 数组存在才处理，纯文本 chunk 没有该字段。
                            for (JsonNode node : tc) { // 一个 chunk 可含多个调用的分片，逐个合并。
                                int idx = node.path("index").asInt(0); // index 对应调用序号，分片可能乱序到达。
                                while (toolCalls.size() <= idx) { // 先补齐占位，保证 index 能直接定位列表位置。
                                    toolCalls.add(new ToolCall(null, "", "")); // 占位 id 为空，真正的 id 只在首个分片出现。
                                } // 结束占位补齐，列表长度覆盖最大 index。
                                ToolCall cur = toolCalls.get(idx); // 取出已有累积值，后续分片在其上合并。
                                String id = node.path("id").asText(""); // id 仅首个分片携带，后续为空须保留旧值。
                                String name = node.path("function").path("name").asText(""); // 函数名同样只在首个分片出现，合并时不能覆盖成空。
                                String args = node.path("function").path("arguments").asText(""); // arguments 是逐字符拼接的 JSON 片段，必须原样累积。
                                toolCalls.set(idx, merge(cur, id, name, args)); // 合并后写回同一 index，保持分片顺序语义。
                            } // 结束单个 chunk 的工具分片合并。
                        } // 结束工具调用数组处理。
                    } // 结束 choices 增量处理。
                    // token 用量（流式通常只在最后一个 chunk 带 usage）
                    JsonNode usage = chunk.path("usage"); // usage 可能在最后一个独立 chunk 出现，也可能完全缺失。
                    if (!usage.isMissingNode()) { // 用 isMissingNode 判断，避免把空对象误当用量。
                        promptTokens = usage.path("prompt_tokens").asInt(0); // 取 prompt_tokens 作为计费输入量。
                        completionTokens = usage.path("completion_tokens").asInt(0); // 取 completion_tokens 作为输出量，缺失时保留上一个已知值。
                    } // 结束用量解析。
                } // 读到流末尾退出，连接关闭由 try-with-resources 负责。
            } // 结束响应读取块，自动关闭 BufferedReader 与底层流。
            return new ChatResponse(content.toString(), toolCalls, // 文本与工具调用聚合完成，返回统一响应结构。
                    new ChatResponse.Usage(promptTokens, completionTokens)); // 续行写入累计用量，供应商未返回时为 0。
        } catch (Exception e) { // 任何 IO、解析或中断异常都收敛到这里。
            throw new IllegalStateException("LLM 请求失败: " + e.getMessage(), e); // 统一包装为运行时异常并保留原因，Worker 据此判定失败或重试。
        } // 结束异常处理块。
    } // 结束 doStream，流式路径不泄漏连接。

    private static ToolCall merge(ToolCall cur, String id, String name, String args) { // 分片合并集中为纯函数，便于验证乱序与缺失字段。
        String mergedId = id.isEmpty() ? cur.id() : id; // id 分片为空时沿用旧值，防止后续分片把标识清空。
        String mergedName = name.isEmpty() ? cur.name() : name; // 函数名同理只认首个非空值。
        String mergedArgs = (cur.argumentsJson() == null ? "" : cur.argumentsJson()) + args; // 参数字符串必须拼接而非覆盖，否则 JSON 会被截断。
        return new ToolCall(mergedId, mergedName, mergedArgs); // 返回新的不可变调用，避免修改列表中已聚合的旧对象。
    } // 结束 merge 辅助方法。

    /** 解析非流式完整响应。 */
    private ChatResponse parseFullResponse(String resp, StreamHandler handler) { // 非流式响应与流式聚合结果对齐，上层无需分支。
        JsonNode root = Json.parse(resp); // 整体响应应为一次合法 JSON，解析失败抛运行时异常。
        JsonNode choices = root.path("choices"); // choices 可能缺失，统一走 path 使缺失时返回 MissingNode。
        String content = ""; // 默认空文本，工具调用型响应可能没有 content。
        List<ToolCall> toolCalls = new ArrayList<>(); // 无工具调用时保持空列表，避免上层出现 null 判断分支。
        if (choices.isArray() && choices.size() > 0) { // 无候选视为空响应，不强行构造假消息。
            JsonNode msg = choices.get(0).path("message"); // 只取首个候选的 message，兼容单轮补全语义。
            content = msg.path("content").asText(""); // content 字段可能为 null，asText 缺省为空串。
            JsonNode tc = msg.path("tool_calls"); // tool_calls 在纯文本响应中缺失，需要判空。
            if (tc.isArray()) { // 数组存在才解析，防止对 MissingNode 迭代出错。
                for (JsonNode node : tc) { // 逐个解析完整调用，非流式响应没有分片需要合并。
                    toolCalls.add(new ToolCall( // 一次性构造调用对象，参数已是完整 JSON 文本。
                            node.path("id").asText(""), // id 缺失时为空串，执行层据此判断协议不完整。
                            node.path("function").path("name").asText(""), // 函数名缺失同样留空，交由工具注册表报未知工具。
                            node.path("function").path("arguments").asText(""))); // arguments 保留模型原始文本，执行前必须再次校验 JSON。
                } // 结束单个工具调用解析。
            } // 结束 tool_calls 数组处理。
        } // 结束 choices 解析，空响应直接返回空内容。
        if (handler != null && !content.isEmpty()) { // 非流式也要回调一次全文，保证上层展示逻辑统一。
            handler.onContentDelta(content); // 一次性推送完整文本，视为单个增量。
        } // 结束非流式回调。
        JsonNode usage = root.path("usage"); // usage 可能缺失，path 取值缺省为 0。
        return new ChatResponse(content, toolCalls, // 返回与流式同结构的响应，工具调用列表可能为空。
                new ChatResponse.Usage(usage.path("prompt_tokens").asInt(0), // 输入 token 缺省 0，结算层据此判断供应商未提供用量。
                        usage.path("completion_tokens").asInt(0))); // 输出 token 缺省 0，不伪造计费数字。
    } // 结束 parseFullResponse。

    private String buildBody(ChatRequest request, boolean stream) { // 请求体构造抽成一处，流式与非流式共享字段拼装规则。
        ObjectNode root = Json.mapper().createObjectNode(); // 以对象节点构建，按需 put 避免 null 字段被序列化。
        root.put("model", model); // 模型取客户端配置而非请求入参，防止绕过服务端模型快照。
        root.put("temperature", request.temperature()); // 温度透传给供应商，Agent 循环依赖低随机性复现行为。
        root.put("max_tokens", request.maxTokens()); // 限制单轮输出长度，控制费用与上下文膨胀。
        root.put("stream", stream); // 由调用方显式指定传输模式，服务端据此选择 SSE 或整体响应。
        if (stream) { // 流式请求显式要求最终用量，便于兼容服务返回计费统计。
            root.putObject("stream_options").put("include_usage", true); // 将真实 token 用量提供给配额结算。
        } // 非流式请求无需设置流式选项。

        ArrayNode messages = root.putArray("messages"); // 消息数组顺序即对话时序，不能重排。
        for (ChatMessage m : request.messages()) { // 逐条转换历史消息，工具结果依赖此顺序回连。
            ObjectNode mn = messages.addObject(); // 每条消息独立对象，后续条件字段只影响当前条。
            mn.put("role", m.role()); // 角色必填，缺失会导致服务端返回 400。
            if (m.content() != null) { // content 为空时不写字段，工具调用消息需要省略它。
                mn.put("content", m.content()); // 写入文本内容，保留原始换行不做转义。
            } // 结束 content 条件写入。
            if (m.toolCallId() != null) { // 仅 tool 消息带 tool_call_id，其他角色不能出现该字段。
                mn.put("tool_call_id", m.toolCallId()); // tool_call_id 必须与 assistant 中记录的 id 完全一致。
            } // 结束工具结果关联字段。
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) { // 确有工具调用才写 tool_calls 数组。
                ArrayNode calls = mn.putArray("tool_calls"); // 一次可写多个调用，顺序与模型发起顺序一致。
                for (ToolCall c : m.toolCalls()) { // 逐个序列化调用，id/name/arguments 必须齐全。
                    ObjectNode cn = calls.addObject(); // 每次调用独立对象，便于类型字段固定。
                    cn.put("id", c.id() == null ? c.name() : c.id()); // id 缺失时退化用函数名，保证回连时有可用标识。
                    cn.put("type", "function"); // 协议固定 type 为 function，其他类型供应商不支持。
                    ObjectNode fn = cn.putObject("function"); // 函数描述单独嵌套，符合 OpenAI tools 协议结构。
                    fn.put("name", c.name()); // 函数名须与本地工具注册表一致，否则执行层拒绝。
                    fn.put("arguments", c.argumentsJson() == null ? "{}" : c.argumentsJson()); // arguments 缺省给空对象，避免服务端收到 null 字符串。
                } // 结束单个工具调用序列化。
                mn.remove("content"); // 带工具调用的 assistant 消息移除 content，满足协议要求。
            } // 结束 tool_calls 条件写入。
        } // 结束消息遍历，数组构建完成。
        if (request.tools() != null && !request.tools().isEmpty()) { // tools 为空时不写字段，纯对话请求保持精简。
            root.set("tools", Json.mapper().valueToTree(request.tools())); // 工具清单转为节点树，与供应商 schema 保持原样。
        } // 结束工具清单拼接。
        return Json.write(root); // 序列化为 JSON 字符串，失败由调用方异常路径处理。
    } // 结束 buildBody。

    private HttpURLConnection open(String url) throws Exception { // 建连与鉴权集中在此，所有请求共享超时与取消检查。
        if (cancelled) throw new IllegalStateException("模型调用已取消"); // 取消之后禁止开启下一轮连接。
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection(); // 按 URL 打开连接，协议由 URI 决定以同时兼容 http 与 https。
        activeConnection = conn; // 将当前连接暴露给协作式取消入口。
        if (cancelled) { conn.disconnect(); throw new IllegalStateException("模型调用已取消"); } // 处理创建连接期间发生取消的竞争。
        conn.setRequestMethod("POST"); // 模型接口只接受 POST，显式声明避免默认 GET。
        conn.setConnectTimeout(10_000); // 连接超时较短，网络不可达时快速失败交给重试逻辑。
        conn.setReadTimeout(120_000); // 读超时较长，模型生成慢但需区分慢与断。
        conn.setRequestProperty("Content-Type", "application/json"); // 请求体为 JSON，缺失该头会被兼容服务拒绝。
        if (apiKey != null && !apiKey.isBlank()) { // 无 Key 时跳过鉴权，兼容本地免鉴权部署。
            conn.setRequestProperty("Authorization", "Bearer " + apiKey); // 标准 Bearer 鉴权，Key 只在请求头出现不落日志。
        } // 结束鉴权头条件设置。
        return conn; // 返回已配置好的连接，由调用方负责读写与关闭。
    } // 结束 open。

    private String httpPost(String url, String body) { // 一次性 POST 封装，非流式路径复用同一套错误处理。
        try { // 异常统一在方法出口转换，调用方无需处理受检异常。
            HttpURLConnection conn = open(url); // 复用 open 以获得超时、鉴权与取消检查。
            conn.setDoOutput(true); // 需要写请求体，必须开启输出流。
            try (OutputStream os = conn.getOutputStream()) { // try-with-resources 确保请求体流关闭后才读取响应。
                os.write(body.getBytes(StandardCharsets.UTF_8)); // UTF-8 写出请求体，中文提示词不乱码。
            } // 结束请求体输出块。
            int code = conn.getResponseCode(); // 读取状态码会触发实际发送，网络错误在此暴露。
            if (code != 200) { // 非 200 视为失败，错误响应不能当模型输出。
                throw new IllegalStateException("HTTP " + code + ": " + readBody(conn.getErrorStream())); // 带响应体内容便于定位参数或鉴权错误。
            } // 结束状态码判断。
            return readBody(conn.getInputStream()); // 读取完整响应文本，交给 JSON 解析层。
        } catch (Exception e) { // 捕获网络与读取异常，统一向外抛出运行时异常。
            throw new IllegalStateException("LLM 请求失败: " + e.getMessage(), e); // 保留原始异常作为原因，Worker 据此判断可重试性。
        } // 结束异常处理。
    } // 结束 httpPost。

    private static String readBody(java.io.InputStream in) throws Exception { // 错误流与正常流复用同一读取逻辑，null 流返回空串。
        if (in == null) { // 错误流可能为 null，如连接未建立时必须防御。
            return ""; // 无内容时返回空串，避免错误信息出现 null 字样。
        } // 结束空流判断。
        StringBuilder sb = new StringBuilder(); // 累积整段响应体，SSE 之外都是一次性 JSON。
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { // 读完即关闭底层流，防止连接泄漏或无法复用。
            String line; // 保存当前行用于循环条件。
            while ((line = br.readLine()) != null) { // 读到流末尾结束，不依赖固定长度。
                sb.append(line); // 逐行拼接，JSON 换行不影响语义。
            } // 结束读取循环。
        } // 结束流关闭块。
        return sb.toString(); // 返回拼接结果，调用方负责解析。
    } // 结束 readBody。

    @Override // 覆盖 AutoCloseable，任务结束时统一释放资源。
    public void close() { // 关闭即取消，确保残留连接不继续占用线程。
        cancel(); // 任务结束或服务关闭后释放仍存活的网络连接。
    } // 结束 close，与 cancel 共用同一断连路径。
    @Override public void cancel() { cancelled = true; HttpURLConnection current = activeConnection; if (current != null) current.disconnect(); } // 尽力中断网络等待，已发生的模型费用无法撤销。
} // 结束 OpenAiCompatClient 类，连接与取消语义收口在此。
