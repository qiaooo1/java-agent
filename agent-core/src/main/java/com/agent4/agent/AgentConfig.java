package com.agent4.agent; // 包声明：配置只被同包的引擎与工具层读取，不扩散到外部依赖。

/**
 * Agent 运行配置（Builder 模式，全部有默认值）。
 *
 * <p>这些参数控制 ReAct 循环的行为边界，是「防止 Agent 失控」的关键：
 * 步数上限、单次 token 上限、工具超时、连续失败熔断、滑动窗口预算，
 * 共同保证 Agent 不会因为模型陷入死循环或上下文无限膨胀而烧穿成本。</p>
 */
public final class AgentConfig { // final 类：配置在构造后冻结，可被多个请求安全共享。

    /** 调用的模型名（如 deepseek-chat）。 */
    private final String model; // 模型名在构造时固化，避免运行期被配置中心变更而中途换模型。
    /** 采样温度：越低越稳定（工具调用场景推荐 0.3 左右）。 */
    private final double temperature; // 温度越低输出越确定，工具调用需要稳定的参数格式。
    /** 单次 LLM 回复的最大 token 数。 */
    private final int maxTokens; // 单轮上限直接约束模型侧计费与响应时长。
    /** ReAct 最大迭代步数，超过即中止，防止死循环空转。 */
    private final int maxIterations; // 迭代上限是防死循环的总闸门，达到即判定未收敛。
    /** 单次工具调用的超时时间（毫秒），超时转为错误观察回传。 */
    private final long toolTimeoutMs; // 单个工具超时，超时由工具层转成错误文本，而不是中断整个循环。
    /** 滑动窗口记忆的消息条数上限。 */
    private final int memoryWindowMessages; // 记忆条数上限，与字符预算共同决定每次请求的上下文规模。
    /** 滑动窗口记忆的字符预算上限（与条数上限取更严者）。 */
    private final int memoryWindowChars; // 字符预算防止单条超长工具输出瞬间撑爆上下文窗口。
    /** 连续失败熔断阈值：连续 N 步工具调用全失败则中止。 */
    private final int consecutiveFailureLimit; // 连续失败阈值，用于区分偶发错误与系统性故障。
    /** 是否启用 SSE 流式输出。 */
    private final boolean stream; // 是否流式，决定 UI 逐字渲染还是一次性展示。

    private AgentConfig(Builder b) { // 私有构造：只允许 Builder 调用，保证所有字段一次性装配完成。
        this.model = b.model; // 从 Builder 拷贝模型名，此后配置与 Builder 互不影响。
        this.temperature = b.temperature; // 温度快照随配置冻结，不随会话漂移。
        this.maxTokens = b.maxTokens; // 单轮 token 上限固化，便于调用方预估最坏计费。
        this.maxIterations = b.maxIterations; // 步数上限固化，运行中不允许被抬高。
        this.toolTimeoutMs = b.toolTimeoutMs; // 工具超时以毫秒固化，避免各处换算出错。
        this.memoryWindowMessages = b.memoryWindowMessages; // 消息条数上限在构造记忆窗口时使用。
        this.memoryWindowChars = b.memoryWindowChars; // 字符预算同样传入记忆窗口，二者取先触发者裁剪。
        this.consecutiveFailureLimit = b.consecutiveFailureLimit; // 熔断阈值固化，中止原因才能被如实写入轨迹。
        this.stream = b.stream; // 流式开关固化，请求组装时直接使用。
    } // 构造结束，配置对象自此完全只读。

    public static Builder builder() { // 静态工厂是唯一入口，调用方不必了解私有构造。
        return new Builder(); // 每次新建 Builder，杜绝共享可变默认实例造成的配置串味。
    } // 结束 builder 工厂方法。

    public String model() { // 只读访问器：外部拿不到 Builder，也就无法回改已生效的配置。
        return model; // 返回构造时快照的模型名。
    } // 结束 model 访问器。

    public double temperature() { // 采样温度供 LLMClient 组装请求时读取。
        return temperature; // 返回快照值，调用方无法在此处产生副作用。
    } // 结束 temperature 访问器。

    public int maxTokens() { // 单轮回复上限，用于限制最坏情况下的费用。
        return maxTokens; // 只读返回，避免不同请求之间出现差异。
    } // 结束 maxTokens 访问器。

    public int maxIterations() { // 循环用该值与 step 比较，是防止空转的第一道闸门。
        return maxIterations; // 只读暴露，运行中不得提高上限。
    } // 结束 maxIterations 访问器。

    public long toolTimeoutMs() { // 工具层据此设置超时，超时后统一转成错误观察回传模型。
        return toolTimeoutMs; // 毫秒单位在配置期确定，运行期不再换算。
    } // 结束 toolTimeoutMs 访问器。

    public int memoryWindowMessages() { // 构造记忆窗口时读取的消息条数上限。
        return memoryWindowMessages; // 只读返回，窗口预算不可再被调整。
    } // 结束 memoryWindowMessages 访问器。

    public int memoryWindowChars() { // 字符预算与条数上限同时生效，先触发者淘汰旧消息。
        return memoryWindowChars; // 返回保守的字符预算，而非精确 token 数。
    } // 结束 memoryWindowChars 访问器。

    public int consecutiveFailureLimit() { // 熔断阈值：连续失败达到该值即中止，避免持续烧 token。
        return consecutiveFailureLimit; // 只读暴露，使中止原因可被如实记录。
    } // 结束 consecutiveFailureLimit 访问器。

    public boolean stream() { // 是否走 SSE 流式输出。
        return stream; // 返回布尔快照，UI 据此决定渲染方式。
    } // 结束 stream 访问器。

    public static final class Builder { // 嵌套 Builder 只在装配阶段存在，避免成为可被外部继承的独立类型。
        private String model = "deepseek-chat"; // 默认模型开箱即用，教学场景无需额外配置即可跑通循环。
        private double temperature = 0.3; // 默认低温度，减少工具参数幻觉。
        private int maxTokens = 2048; // 默认单轮上限，足够给出答案又能限制单次计费。
        private int maxIterations = 10; // 默认十步：给多轮工具调用留余量，死循环时也能及时止损。
        private long toolTimeoutMs = 30_000; // 默认三十秒超时，覆盖慢速外部接口，超时后让模型自愈。
        private int memoryWindowMessages = 40; // 默认四十条消息，约十轮 ReAct 往返，兼顾上下文与成本。
        private int memoryWindowChars = 60_000; // 默认六万字符预算，防止单条超长输出一次撑爆窗口。
        private int consecutiveFailureLimit = 3; // 默认连续三步全失败才熔断，避开偶发抖动。
        private boolean stream = true; // 默认开启流式，降低首字延迟。

        public Builder model(String model) { // 链式 setter：返回 Builder 自身，便于一行装配全部参数。
            this.model = model; // 直接覆盖默认值，非法值留待运行时暴露，保持装配简单。
            return this; // 返回 this 以支持继续链式调用。
        } // 结束 model 配置。

        public Builder temperature(double temperature) { // 温度越高越发散，演示时可经此入口调整。
            this.temperature = temperature; // 覆盖默认的 0.3。
            return this; // 返回 this 保持构建链。
        } // 结束 temperature 配置。

        public Builder maxTokens(int maxTokens) { // 单轮上限按模型能力由调用方设定。
            this.maxTokens = maxTokens; // 写入字段，只影响此后 build 出的配置。
            return this; // 返回 this。
        } // 结束 maxTokens 配置。

        public Builder maxIterations(int maxIterations) { // 步数上限可在演示中调小，快速观察中止路径。
            this.maxIterations = maxIterations; // 覆盖默认的 10 步。
            return this; // 返回 this。
        } // 结束 maxIterations 配置。

        public Builder toolTimeoutMs(long toolTimeoutMs) { // 超时按毫秒配置，测试可注入极短超时验证错误回传。
            this.toolTimeoutMs = toolTimeoutMs; // 覆盖默认的 30 秒。
            return this; // 返回 this。
        } // 结束 toolTimeoutMs 配置。

        public Builder memoryWindowMessages(int n) { // 参数名 n 为简写，单位是消息条数。
            this.memoryWindowMessages = n; // 覆盖默认窗口条数。
            return this; // 返回 this。
        } // 结束 memoryWindowMessages 配置。

        public Builder memoryWindowChars(int n) { // 预算调大可容纳更长工具输出，但会推高每次请求成本。
            this.memoryWindowChars = n; // 覆盖默认字符预算。
            return this; // 返回 this。
        } // 结束 memoryWindowChars 配置。

        public Builder consecutiveFailureLimit(int n) { // 阈值调小可快速演示熔断。
            this.consecutiveFailureLimit = n; // 覆盖默认阈值。
            return this; // 返回 this。
        } // 结束 consecutiveFailureLimit 配置。

        public Builder stream(boolean stream) { // 开关最终传给 ChatRequest，决定客户端走哪个接口。
            this.stream = stream; // 覆盖默认的流式开启。
            return this; // 返回 this。
        } // 结束 stream 配置。

        public AgentConfig build() { // build 才做整体拷贝，Builder 之后继续可改而不污染已建实例。
            return new AgentConfig(this); // 通过私有构造完成字段冻结，外部无法绕过装配路径。
        } // 结束 build。
    } // 结束 Builder 类，其可变状态仅存在于装配阶段。
} // 结束 AgentConfig，全类不可变。
