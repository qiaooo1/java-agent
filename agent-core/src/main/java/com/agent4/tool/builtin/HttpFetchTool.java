package com.agent4.tool.builtin; // 内置抓取工具与计算器等放在同一包，便于统一注册。

import com.agent4.tool.Tool; // 方法级注解，注册中心据此发现工具。
import com.agent4.tool.ToolParam; // 参数注解，用于生成参数描述。

import java.io.BufferedReader; // 按行读取响应，控制大页面的内存峰值。
import java.io.InputStreamReader; // 把字节流按指定字符集解码为文本。
import java.net.HttpURLConnection; // 只依赖 JDK 自带 HTTP 实现，不引入额外客户端。
import java.net.URI; // 先做 URL 语法校验，非法地址在此阶段抛错。
import java.nio.charset.StandardCharsets; // 固定 UTF-8 解码，避免中文页面乱码。
import java.util.regex.Pattern; // 预编译清洗正则，避免每次抓取重复编译。

/**
 * HTTP 抓取工具：抓取网页正文（去掉 HTML 标签），供 Agent 获取实时信息。
 */
public class HttpFetchTool { // 非 final，实例方法由注册中心扫描为工具。

    private static final Pattern TAG = Pattern.compile("<[^>]+>"); // 匹配任意 HTML 标签整体，用于粗粒度去标签。
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>"); // (?is) 让点号匹配换行且忽略大小写，整段删除脚本与样式。

    @Tool(name = "fetch_url", description = "抓取指定 URL 的网页正文（去除 HTML 标签后的纯文本，用于获取实时信息）") // 描述明确产出是纯文本，引导模型只在需要实时信息时调用。
    public String fetch(@ToolParam("要抓取的完整 URL，如 https://example.com") String url) { // 返回纯文本 Observation，所有异常都必须转字符串而不能上抛。
        try { // 网络、编码、解析都可能失败，统一在此兜底。
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection(); // URI 先校验语法，openConnection 不建连，取状态码才真正请求。
            conn.setRequestMethod("GET"); // 只读语义，明确使用 GET。
            conn.setConnectTimeout(8_000); // 建连超时 8 秒，避免网络不可达时长时间挂起。
            conn.setReadTimeout(15_000); // 读超时 15 秒，避免慢速响应拖死工作线程。
            conn.setRequestProperty("User-Agent", // 部分站点拒绝无 UA 的请求，这里伪装常见浏览器。
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"); // 使用静态 UA 字符串，保持请求可复现。
            int code = conn.getResponseCode(); // 取状态码时才真正发起请求。
            if (code != 200) { // 只接受 200，不自动跟随重定向，避免抓取范围不可控。
                return "错误: HTTP " + code; // 回传状态码，让模型判断是地址错误还是站点拒绝。
            } // 状态码判断结束。
            StringBuilder sb = new StringBuilder(); // 累积原始响应文本，随后统一清洗。
            try (BufferedReader br = new BufferedReader( // try-with-resources 保证异常时连接流也会关闭。
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) { // 固定 UTF-8 解码，不信任响应头声明的编码。
                String line; // 行读取变量，逐行拼接可控制内存峰值。
                while ((line = br.readLine()) != null) { // readLine 返回 null 表示响应流结束。
                    sb.append(line).append('\n'); // 补回换行，保留段落结构供后续清洗。
                } // 读取循环结束。
            } // try-with-resources 结束，流已关闭。
            String html = SCRIPT_STYLE.matcher(sb.toString()).replaceAll(" "); // 先删脚本样式内容，避免其代码文本混入正文。
            String text = TAG.matcher(html).replaceAll(" "); // 去标签并留空格分隔，防止相邻文字粘连。
            String cleaned = text.replaceAll("&nbsp;|&amp;|&lt;|&gt;", " ") // 常见 HTML 实体转空格，保留可读文本。
                    .replaceAll("\\s+", " ").trim(); // 压缩连续空白并去首尾，减小无意义体积。
            // 截断到 4000 字符，避免撑爆上下文
            return cleaned.length() > 4000 ? cleaned.substring(0, 4000) + "..." : cleaned; // 硬上限 4000 字符，超限加省略号提示被截断。
        } catch (Exception e) { // 兜底分支：任何失败都降级为错误观察而非抛异常。
            return "错误: 抓取失败: " + e.getMessage(); // 带上原因，让模型可改 URL 或放弃重试。
        } // try-catch 结束。
    } // fetch 方法结束。
} // 类体结束。
