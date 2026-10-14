package com.test.blog.tools.service;

/** 字段字节数超限的业务信号，由 HTTP 层转换为 413，不携带原始输入。 */
public class InputTooLargeException extends RuntimeException {
}
