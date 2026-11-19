package com.agent4.platform.api; // 统一 HTTP 错误边界，避免数据库和堆栈泄露。
import com.agent4.backend.BusinessException; // 已定义的领域错误。
import org.springframework.web.bind.annotation.*; // 控制器异常映射。
import org.springframework.http.ResponseEntity; // 明确设置响应状态。
import org.springframework.web.bind.MethodArgumentNotValidException; // 处理字段校验失败。
import org.springframework.web.HttpRequestMethodNotSupportedException; // 保留请求方法不受支持时的状态与 Allow 响应头。
import org.springframework.web.HttpMediaTypeNotSupportedException; // 保留请求体类型不受支持时的状态与 Accept 响应头。
import org.slf4j.*; // 服务端保留真正故障日志。
import java.util.Map; // 使用稳定的错误 JSON。
@RestControllerAdvice // 覆盖平台 REST API。
public class ApiErrors { // 安全链错误仍由安全链处理。
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class); // 不将内部异常直接返回客户端。
    @ExceptionHandler(BusinessException.class) ResponseEntity<?> business(BusinessException e) { return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage())); } // 领域冲突保留明确原因。
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message", "输入字段格式或长度不正确")); } // 参数错误不启动任务。
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.bind.MissingRequestHeaderException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<?> malformed(Exception e) { return ResponseEntity.badRequest().body(Map.of("message", "请求 JSON、必需请求头或参数格式不正确")); } // 协议错误不能错误映射成服务内部故障。
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) // 已知的客户端方法错误优先于 Exception 兜底。
    ResponseEntity<?> methodNotAllowed(HttpRequestMethodNotSupportedException e) { // 直接复用框架识别的合法方法，避免维护重复清单。
        return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders()).body(Map.of("message", "请求方法不受支持")); // 返回 405 和 Allow，同时保持统一 JSON 结构。
    } // 结束请求方法错误映射。
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class) // 不支持的请求体格式不是服务器内部异常。
    ResponseEntity<?> unsupportedMediaType(HttpMediaTypeNotSupportedException e) { // 支持的媒体类型由实际消息转换器决定。
        return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders()).body(Map.of("message", "请求内容类型不受支持")); // 返回 415 和 Accept，不暴露转换器内部异常。
    } // 结束请求内容类型错误映射。
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) { log.error("platform_request_failed", e); return ResponseEntity.status(500).body(Map.of("message", "服务暂时不可用，请稍后重试")); } // 不暴露 SQL、密钥与堆栈。
} // 结束错误映射。
