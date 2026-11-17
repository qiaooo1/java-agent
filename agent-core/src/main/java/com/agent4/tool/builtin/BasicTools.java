package com.agent4.tool.builtin; // 内置工具单独分包，与框架层 Tool 注解解耦。

import com.agent4.tool.Tool; // 方法级注解，注册中心据此发现工具。
import com.agent4.tool.ToolParam; // 参数注解，用于生成 JSON Schema 描述。

import java.time.LocalDateTime; // 取目标时区的本地时间。
import java.time.ZoneId; // 时区标识，支持 IANA 名称解析。
import java.time.format.DateTimeFormatter; // 固定输出格式，避免依赖系统区域设置。
import java.util.Map; // 预留 Map 类型，便于后续多参数工具以键值形式声明入参。

/**
 * 基础工具：计算器 + 时钟。
 */
public class BasicTools { // 非 final，便于测试继承替换；实例整体交给注册中心扫描。

    @Tool(name = "calculator", description = "计算一个数学表达式（支持 + - * / 括号，如 1+2*3）") // 声明工具名与描述，注册后进入模型可见的工具清单。
    public String calculator(@ToolParam("数学表达式字符串") String expression) { // 返回 String 作为 Observation，模型可直接阅读。
        try { // 表达式由模型生成，必须兜住解析失败。
            double result = Expression.eval(expression); // 交给递归下降求值器，只支持四则与括号。
            return "结果: " + (result == Math.floor(result) && !Double.isInfinite(result) // 整数结果去掉 ".0"，避免模型误判为浮点。
                    ? String.valueOf((long) result) : String.valueOf(result)); // 非整数或无穷值按原样输出。
        } catch (Exception e) { // 任何解析/除零异常都转成可观察的错误文本。
            return "错误: 无法计算表达式 '" + expression + "': " + e.getMessage(); // 回显原表达式，便于模型自我纠正后重试。
        } // try-catch 结束。
    } // calculator 方法结束。

    @Tool(name = "get_time", description = "获取当前日期和时间") // 时钟工具让模型获得真实当前时间，不靠训练数据猜测。
    public String getTime( // 时区可选，缺省使用运行环境的系统时区。
            @ToolParam(value = "时区，如 Asia/Shanghai，默认系统时区", required = false) String timezone) { // required=false 保证 Schema 不把它列入必填。
        try { // 非法时区会抛异常，需转为观察结果。
            ZoneId zone = timezone == null || timezone.isBlank() // 空值与空白串都视为未指定时区。
                    ? ZoneId.systemDefault() : ZoneId.of(timezone); // 指定时区按 IANA 名称解析，失败则进入 catch。
            LocalDateTime now = LocalDateTime.now(zone); // 按目标时区取当前本地时间，而非 UTC。
            return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " (" + zone + ")"; // 固定格式并附时区，避免模型误解时间基准。
        } catch (Exception e) { // 时区非法统一降级为错误文本。
            return "错误: 无效时区 '" + timezone + "'"; // 列出非法输入，引导模型换正确时区重试。
        } // try-catch 结束。
    } // getTime 方法结束。

    /** 简单四则表达式求值器（递归下降，不依赖第三方库）。 */
    static final class Expression { // 静态内部类，不依赖外部实例，纯函数式求值。
        private final String s; // 待解析的原始表达式，构造后只读。
        private int pos; // 当前扫描位置，递归下降靠它推进。

        private Expression(String s) { // 私有构造，强制通过 eval 入口使用。
            this.s = s; // 保存表达式文本。
        } // 构造结束。

        static double eval(String s) { // 唯一入口：新建解析器并立即求值。
            return new Expression(s).parseExpr(); // 从最外层表达式开始自顶向下归约。
        } // eval 结束。

        private double parseExpr() { // 加减层：优先级最低，位于表达式顶层。
            double v = parseTerm(); // 先取一个乘除项作为左操作数。
            while (true) { // 循环消费同级运算符，保证左结合。
                skipWs(); // 跳过空白，模型常带多余空格。
                if (peek('+')) { // peek 只读不消费，仅为判断。
                    pos++; // 确认后越过运算符。
                    v += parseTerm(); // 右侧继续按更高优先级解析。
                } else if (peek('-')) { // 减法与加法同层。
                    pos++; // 越过减号。
                    v -= parseTerm(); // 累减到左操作数。
                } else { // 没有同级运算符说明本层处理完毕。
                    return v; // 返回本层结果给上层。
                } // 运算符分支结束。
            } // 同级循环结束。
        } // parseExpr 结束。

        private double parseTerm() { // 乘除层，优先级高于加减。
            double v = parseFactor(); // 取因子作为左操作数。
            while (true) { // 消费连续乘除。
                skipWs(); // 容忍空白。
                if (peek('*')) { // 乘法先于加减结合。
                    pos++; // 越过乘号。
                    v *= parseFactor(); // 右操作数取因子，保证优先级正确。
                } else if (peek('/')) { // 除法与乘法同层。
                    pos++; // 越过除号。
                    v /= parseFactor(); // double 除零得 Infinity，由工具层原样输出。
                } else { // 无同级运算符则结束本层。
                    return v; // 返回乘除结果。
                } // 运算符分支结束。
            } // 同级循环结束。
        } // parseTerm 结束。

        private double parseFactor() { // 因子层：处理括号、正负号与数字。
            skipWs(); // 先跳空白再判断首字符。
            if (peek('(')) { // 括号提升优先级，内部重新从表达式层解析。
                pos++; // 越过左括号。
                double v = parseExpr(); // 递归调用最外层，实现括号内完整求值。
                skipWs(); // 跳过右括号前的空白。
                if (!peek(')')) { // 左括号必须有对应右括号，否则语法非法。
                    throw new IllegalArgumentException("缺少右括号"); // 抛出非法参数异常，由 calculator 捕获转错误文本。
                } // 右括号校验结束。
                pos++; // 越过右括号。
                return v; // 返回括号内结果。
            } // 括号分支结束。
            if (peek('-')) { // 一元负号。
                pos++; // 越过负号。
                return -parseFactor(); // 递归取负，支持连续负号。
            } // 负号分支结束。
            if (peek('+')) { // 一元正号，语义上不改变值。
                pos++; // 越过正号。
                return parseFactor(); // 直接返回后续因子。
            } // 正号分支结束。
            int start = pos; // 记录数字起始下标。
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) { // 只接受数字与小数点，不解析科学计数法。
                pos++; // 推进到数字末尾。
            } // 数字扫描结束。
            if (start == pos) { // 一个字符都没取到，说明遇到非法字符或已到末尾。
                throw new IllegalArgumentException("无效字符 '" + (pos < s.length() ? s.charAt(pos) : ' ') + "'"); // 已到末尾时用空格占位，避免越界。
            } // 非法字符判断结束。
            return Double.parseDouble(s.substring(start, pos)); // 截取数字串转 double，非法格式同样抛异常。
        } // parseFactor 结束。

        private boolean peek(char c) { // 只读查看当前字符，不消费输入。
            skipWs(); // 判断前先跳空白，调用方无需关心空格。
            return pos < s.length() && s.charAt(pos) == c; // 越界返回 false，让循环自然终止。
        } // peek 结束。

        private void skipWs() { // 统一跳空白，避免每处重复实现。
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) { // 只要仍是空白就前进。
                pos++; // 越过一个空白字符。
            } // 空白循环结束。
        } // skipWs 结束。
    } // Expression 结束。
} // BasicTools 类体结束。
