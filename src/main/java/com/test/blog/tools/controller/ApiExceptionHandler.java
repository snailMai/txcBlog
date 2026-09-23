package com.test.blog.tools.controller;

import com.test.blog.tools.service.DecryptionException;
import com.test.blog.tools.service.InputTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ToolsController.class)
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(InputTooLargeException.class)
    public ResponseEntity<ApiError> inputTooLarge(InputTooLargeException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "INPUT_TOO_LARGE", "输入过大，请缩短密钥或文本后重试。");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效，请检查后重试。");
    }

    @ExceptionHandler(DecryptionException.class)
    public ResponseEntity<ApiError> decryptionFailed(DecryptionException exception) {
        return error(HttpStatus.BAD_REQUEST, "DECRYPTION_FAILED", "解密失败，请检查密钥和密文。");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpectedFailure(Exception exception) {
        // Exception text and stack traces can include request data; only log the exception type.
        log.error("Tool request failed: {}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试。");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store").body(new ApiError(code, message));
    }
}
