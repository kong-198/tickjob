package com.kong.tickjob.admin.common;

import com.kong.tickjob.common.exception.TickJobException;
import com.kong.tickjob.common.protocol.RpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>响应体统一用 {@link RpcResponse}，同时把 HTTP 状态码也设成对应的语义 ——
 * 只返回 200 再在 body 里写错误码的做法，会让所有网关、监控、重试组件
 * 都误以为请求是成功的。</p>
 *
 * <p>响应体里的 {@code code} 与 HTTP 状态码保持同一个值，不额外造一套码表：
 * 「cron 写错了」是 400，客户端改一下请求就能过；「服务端炸了」才是 500，
 * 这两个结论必须让调用方一眼分得清。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务 / 配置类错误：cron 写错了、任务不存在、策略名非法 */
    @ExceptionHandler(TickJobException.class)
    public ResponseEntity<RpcResponse<Void>> handleBiz(TickJobException e) {
        log.warn("请求被拒绝：{}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(RpcResponse.fail(RpcResponse.CODE_BAD_REQUEST, e.getMessage()));
    }

    /** 参数校验失败：把每个字段的错误拼起来，前端可以直接展示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RpcResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest()
                .body(RpcResponse.fail(RpcResponse.CODE_BAD_REQUEST, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RpcResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(RpcResponse.fail(RpcResponse.CODE_BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RpcResponse<Void>> handleUnexpected(Exception e) {
        log.error("服务器内部错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(RpcResponse.fail(RpcResponse.CODE_FAIL, "服务器内部错误：" + e.getMessage()));
    }
}
