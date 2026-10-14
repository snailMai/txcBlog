package com.test.blog.tools.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在 JSON 解析前限制实际请求体大小，防止超大正文先被完整反序列化。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ToolsRequestSizeFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 去掉矩阵参数再匹配，避免 /tools;参数/... 绕过工具路径检查。
        String path = request.getServletPath().replaceAll(";[^/]*", "");
        return !path.equals("/tools") && !path.startsWith("/tools/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        // 多读一个字节判定超限，兼顾未知长度的分块请求和 JSON 后的尾随空白。
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(new BoundedRequest(request, body), response);
    }

    /** 过滤器位于 Controller 之外，必须自行返回安全的 413 和缓存头。 */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"INPUT_TOO_LARGE\",\"message\":\"输入过大，请缩短密钥或文本后重试。\"}");
    }

    /** 原始流已被消耗，下游从已限长的内存副本读取；该副本不得写入日志。 */
    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final ServletInputStream input;

        private BoundedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            var bytes = new ByteArrayInputStream(body);
            input = new ServletInputStream() {
                @Override public int read() { return bytes.read(); }
                @Override public int read(byte[] buffer, int offset, int length) {
                    return bytes.read(buffer, offset, length);
                }
                @Override public boolean isFinished() { return bytes.available() == 0; }
                @Override public boolean isReady() { return true; }
                // 当前接口同步消费正文，不伪装成支持 Servlet 异步读取。
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Tool endpoints use synchronous request bodies");
                }
            };
        }

        @Override public ServletInputStream getInputStream() { return input; }

        @Override public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(input,
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }
}
