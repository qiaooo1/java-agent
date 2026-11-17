package com.agent4.rag; // 分块器属于 RAG 模块，与向量化同包供索引流程复用。

import java.util.ArrayList; // 结果按顺序累积，使用可变列表。
import java.util.List; // 对外以 List 暴露分块结果。

/**
 * 文档分块器：把长文本切成适合检索与注入 prompt 的片段（chunk）。
 *
 * <p>为什么 RAG 要分块？直接把整篇文档丢进 prompt 会撑爆上下文，且检索粒度太粗、
 * 命中不精准。切成适度大小的块，检索时按块召回，只把最相关的几块喂给模型。</p>
 *
 * <p>策略：优先按空行（段落）切，单块超过 {@link #maxChars} 时按句子边界继续切；
 * 块之间保留少量重叠字符（{@code overlap}），避免把一句完整的话切到两块的边界上。</p>
 */
public final class TextChunker { // final 且无状态，可被索引与检索线程共享。

    private final int maxChars; // 单块字符上限，直接决定注入 prompt 的成本与检索粒度。
    private final int overlap; // 相邻块重叠字符数，用于兜住被切断的句子。

    public TextChunker(int maxChars, int overlap) { // 参数由调用方按模型上下文预算设定，类内不写死。
        this.maxChars = maxChars; // 保存块大小上限。
        this.overlap = overlap; // 保存重叠长度，切分时用它回退起点。
    } // 构造结束：配置在读取文本前固定。

    /** 分块入口。 */
    public List<String> chunk(String text) { // 任意文本都返回块列表，空输入返回空列表而非 null。
        List<String> result = new ArrayList<>(); // 结果按原文顺序累积，顺序影响检索上下文连续性。
        if (text == null || text.isBlank()) { // 空文档没有可检索内容，避免产生空块。
            return result; // 返回空列表，调用方无需判空。
        } // 空输入分支结束。
        // 统一换行，先按空行切成大段
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n'); // 统一 CRLF/CR 为 LF，避免换行符差异导致分块不一致。
        for (String paragraph : normalized.split("\n\\s*\n")) { // 空行即段落边界，\\s* 容忍行间多余空格。
            result.addAll(chunkSingle(paragraph)); // 段落内部再按大小切，保证每块不超过 maxChars。
        } // 段落循环结束。
        return result; // 返回全部分块。
    } // chunk 方法结束。

    /** 单个段落（无空行）再按大小切。 */
    private List<String> chunkSingle(String paragraph) { // 段落级切分；短段落原样成块，避免无意义碎片。
        List<String> out = new ArrayList<>(); // 单段落可能产出多个块，需要独立容器。
        String p = paragraph.trim(); // 去掉首尾空白，块边界不应残留多余空格。
        if (p.isEmpty()) { // 纯空白段落（如连续空行）不产出块。
            return out; // 返回空列表表示该段无内容。
        } // 空段落分支结束。
        if (p.length() <= maxChars) { // 未超限的段落整段成块，保留原文语义完整。
            out.add(p); // 直接加入，不做截断。
            return out; // 短段落无需继续切分。
        } // 短段落分支结束。
        // 超长段落：按句子边界（。！？；换行）切，尽量不切断句子
        int start = 0; // 当前块起始下标，按重叠策略向前推进。
        while (start < p.length()) { // 未处理完则继续产块，直到覆盖全文。
            int end = Math.min(start + maxChars, p.length()); // 候选终点不超块上限，并夹到文本末尾。
            if (end < p.length()) { // 只有块未到文末时才需要找句子边界。
                int cut = lastBoundary(p, start, end); // 向前回溯最近标点，尽量不把句子切断。
                if (cut > start) { // 找到有效边界才采用，否则保留原终点避免死循环。
                    end = cut; // 把块尾收缩到句子边界处。
                } // 边界调整分支结束。
            } // 未到文末判断结束。
            out.add(p.substring(start, end).trim()); // 截取并去空白，保证块内容干净。
            // 前进时保留 overlap 个字符的重叠，避免句子被边界截断
            start = Math.max(start + 1, end - overlap); // 至少前进 1 字符防原地打转，正常回退 overlap 造出重叠。
        } // 切分循环结束。
        return out; // 返回该段落的全部块。
    } // chunkSingle 方法结束。

    /** 在 [start, end) 区间内找最后一个句子边界（。！？；\n）。 */
    private int lastBoundary(String s, int start, int end) { // 只在候选区间内查找，避免结果超出块上限。
        for (int i = end - 1; i > start; i--) { // 从终点向前回溯，取最靠后的边界以获得最大块。
            char c = s.charAt(i); // 逐个检查是否为断句标点。
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n' // 中英文句末标点与换行都视为安全切点。
                    || c == '.' || c == '!' || c == '?') { // 英文标点同样处理，兼容中英混排文本。
                return i + 1; // 返回标点之后的位置，使标点留在前一块结尾。
            } // 标点判断结束。
        } // 回溯循环结束。
        return -1; // 未找到边界返回 -1，调用方据此退回按长度硬切。
    } // lastBoundary 方法结束。
} // 类体结束。
