package com.test.blog.tools.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTests {
    @Test
    void unexpectedFailureDoesNotExposeSensitiveExceptionData(CapturedOutput output) {
        var response = new ApiExceptionHandler().unexpectedFailure(
                new IllegalStateException("synthetic-sensitive-request-data"));
        assertEquals(500, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(new ApiExceptionHandler.ApiError("INTERNAL_ERROR", "服务暂时不可用，请稍后重试。"),
                response.getBody());
        assertFalse(output.getAll().contains("synthetic-sensitive-request-data"));
    }
}
