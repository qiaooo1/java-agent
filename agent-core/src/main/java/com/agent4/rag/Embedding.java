package com.agent4.rag; // 向量化实现归入 RAG 模块，索引与检索共用同一套算法。

import java.util.HashMap; // 词频统计用哈希表按 token 聚合计数。
import java.util.Map; // 以 Map.Entry 遍历词频，读写接口统一。

/**
 * 本地向量化：把文本转成固定维度的稠密向量，用于余弦相似度检索。
 *
 * <p><b>为什么内置而非调 embedding API？</b>演示项目要「零外部依赖、离线稳定、不花钱」。
 * 这里用经典的<b>词袋（Bag-of-Words）+ TF 加权 + 特征哈希（Feature Hashing）</b>：
 * 对中文按字/二元词切分，统计词频，用哈希把词映射到固定维度桶上累加权重，
 * 再做 L2 归一化，得到一个 {@link #dimension} 维向量。</p>
 *
 * <p>这是「浅语义」向量（同义词、语义相近但字面不同的词无法匹配），但足以完整
 * 跑通 RAG 全链路：分块 → 向量化 → 余弦检索 → 注入 prompt。生产环境只需把这一个类
 * 替换成 BGE / sentence-transformers 等 embedding 模型，上下游接口不变——
 * 这正是把「向量化」抽象成独立模块的价值。</p>
 */
public final class Embedding { // final 且无状态，可安全共享；替换为真实模型时保持同一方法签名。

    private final int dimension; // 维度固定：特征哈希依赖固定桶数，改维度会让历史向量不可比较。

    public Embedding(int dimension) { // 由调用方决定维度，索引与查询必须使用同一维度。
        this.dimension = dimension; // 保存维度，embed 开数组与取模都以它为基数。
    } // 构造结束：对象创建后状态不再变化。

    public int dimension() { // 暴露维度，便于检索侧校验两向量长度一致。
        return dimension; // 返回构造时固定的维度。
    } // 维度读取结束。

    /**
     * 文本 → 归一化向量。
     *
     * <p>步骤：① 切词（中文按连续汉字/数字/英文单词，英文字母转小写）；
     * ② 统计词频（TF）；③ 特征哈希到 dimension 维桶并累加 TF；④ L2 归一化。</p>
     */
    public double[] embed(String text) { // 向量化入口：任何输入都返回长度恒为 dimension 的向量。
        double[] vec = new double[dimension]; // 先分配全零向量，未命中的桶保持 0。
        if (text == null || text.isBlank()) { // 空输入不做切词，避免产生无意义 token。
            return vec; // 返回零向量；余弦对零向量会安全地得到 0。
        } // 空输入分支结束。
        Map<String, Integer> tf = tokenize(text); // 先算词频表，同一 token 只哈希一次但权重为出现次数。
        for (Map.Entry<String, Integer> e : tf.entrySet()) { // 逐 token 累加到桶，桶是多个词共享的哈希槽位。
            int idx = hash(e.getKey()) % dimension; // 取模把任意哈希值压到 [0, dimension) 区间。
            if (idx < 0) { // Java 取模可能为负，负下标会抛数组越界，必须纠正。
                idx += dimension; // 加回维度后落入合法区间 [0, dimension)。
            } // 下标纠正分支结束。
            // 简单 TF 加权（未用 IDF，避免全局统计的额外状态；生产可用 TF-IDF 或 BM25）
            vec[idx] += e.getValue(); // 累加词频：多词撞同一桶会叠加，这是特征哈希的碰撞语义。
        } // 词频累加循环结束。
        normalize(vec); // L2 归一化只保留方向信息，余弦退化为点积且不受文本长短影响。
        return vec; // 返回归一化后的定长向量。
    } // embed 方法结束。

    /** 余弦相似度（两向量已归一化时等价于点积）。 */
    public double cosine(double[] a, double[] b) { // 相似度独立成方法，便于检索侧替换为点积优化。
        double dot = 0; // 点积累加器，衡量两向量方向的一致程度。
        double na = 0; // a 的平方和，未归一化时也能算出余弦。
        double nb = 0; // b 的平方和，并用于零向量判断。
        for (int i = 0; i < a.length; i++) { // 以 a 的长度遍历，调用方需保证两向量同维。
            dot += a[i] * b[i]; // 对应维度相乘累加。
            na += a[i] * a[i]; // 顺便累计 a 的模长平方，避免二次遍历。
            nb += b[i] * b[i]; // 顺便累计 b 的模长平方。
        } // 遍历累加结束。
        if (na == 0 || nb == 0) { // 零向量没有方向，余弦无定义，这里约定为不相关。
            return 0; // 返回 0 让检索排序稳定且不会除零。
        } // 零向量保护分支结束。
        return dot / (Math.sqrt(na) * Math.sqrt(nb)); // 按余弦定义归一化点积，结果落在 [-1, 1]。
    } // cosine 方法结束。

    /** 切词：中文按连续汉字/数字分段，英文按单词（转小写）。 */
    private Map<String, Integer> tokenize(String text) { // 切词只产词频表，无位置状态以便并发复用。
        Map<String, Integer> tf = new HashMap<>(); // 每次调用新建词频表，内部状态不外泄。
        StringBuilder cur = new StringBuilder(); // cur 累积当前连续词字符，遇分隔符才结算。
        for (int i = 0; i < text.length(); i++) { // 逐字符扫描，中文没有空格分词只能按字符类别切。
            char c = text.charAt(i); // 取出当前字符做类别判断。
            if (isWordChar(c)) { // 字母/数字/下划线视为词内字符，不在此断开。
                cur.append(Character.toLowerCase(c)); // 统一小写，使 "Java" 与 "java" 落到同一桶。
            } else { // 其余字符（空格标点换行）作为词边界。
                flush(cur, tf); // 遇边界立即结算当前 token。
            } // 字符分类分支结束。
        } // 扫描循环结束。
        flush(cur, tf); // 文本末尾无分隔符时补结算，避免丢掉最后一个词。
        return tf; // 返回该文本的词频表。
    } // tokenize 方法结束。

    private boolean isWordChar(char c) { // 词字符判定单独抽出，便于调整分词口径。
        return Character.isLetterOrDigit(c) || c == '_'; // 用 JDK 的 Unicode 判定，汉字也属于 letter。
    } // isWordChar 方法结束。

    private void flush(StringBuilder cur, Map<String, Integer> tf) { // 结算当前缓冲：拆成 token 并清空缓冲。
        if (cur.length() > 0) { // 空缓冲无需结算，否则会产生空 token。
            String token = cur.toString(); // 取出完整连续串，作为整词候选。
            // 中文 token 较长，按二元词（bigram）切分，提升召回粒度
            if (token.length() > 1 && isChinese(token)) { // 纯中文且长度>1 才做 bigram，单字按原样处理。
                for (int i = 0; i + 1 < token.length(); i++) { // 滑动窗口取相邻两字，上界 length-1 防越界。
                    add(tf, token.substring(i, i + 2)); // 二元词提升召回：单字太泛，整词又太严。
                } // bigram 循环结束。
                add(tf, token); // 同时保留整词
            } else { // 非中文长词（英文单词等）不做切分，整词即语义单元。
                add(tf, token); // 按整词计一次频。
            } // 分词策略分支结束。
            cur.setLength(0); // 清空缓冲，复用同一 StringBuilder 减少对象分配。
        } // 空缓冲判断结束。
    } // flush 方法结束。

    private boolean isChinese(String s) { // 判断整串是否全为汉字，只有纯中文才值得 bigram。
        for (int i = 0; i < s.length(); i++) { // 逐字检查，遇任何非汉字立即否定。
            char c = s.charAt(i); // 取当前字符比对码点区间。
            if (c < 0x4E00 || c > 0x9FFF) { // 0x4E00-0x9FFF 是基本汉字区，超出则不算中文。
                return false; // 提前返回，避免无谓继续扫描。
            } // 码点区间判断结束。
        } // 逐字检查循环结束。
        return true; // 全部通过，说明是纯汉字串。
    } // isChinese 方法结束。

    private void add(Map<String, Integer> tf, String token) { // 统一计数入口，避免各处重复 merge 写法。
        tf.merge(token, 1, Integer::sum); // 已存在则加 1，不存在以 1 初始化，即词频 TF。
    } // add 方法结束。

    /** 字符串哈希（Java String.hashCode 即可，分布足够均匀）。 */
    private int hash(String s) { // 哈希函数集中一处，换实现时索引与查询可保持一致。
        return s.hashCode(); // 用 JDK 内置哈希，稳定可复现，重启后索引仍可命中。
    } // hash 方法结束。

    private void normalize(double[] vec) { // 原地归一化，避免额外分配大数组。
        double sum = 0; // 累加平方和以计算 L2 模长。
        for (double v : vec) { // 遍历所有桶，空桶贡献 0 不影响结果。
            sum += v * v; // 平方累加。
        } // 平方和循环结束。
        if (sum == 0) { // 全零向量无法归一化，直接保留零向量。
            return; // 提前返回避免除以 0 产生 NaN。
        } // 零模长保护结束。
        double norm = Math.sqrt(sum); // 模长只算一次，循环内复用。
        for (int i = 0; i < vec.length; i++) { // 逐桶除以模长。
            vec[i] /= norm; // 使向量长度为 1，后续点积即余弦。
        } // 归一化循环结束。
    } // normalize 方法结束。
} // 类体结束。
