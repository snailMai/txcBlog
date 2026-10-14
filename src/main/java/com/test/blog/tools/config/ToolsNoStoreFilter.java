package com.test.blog.tools.config;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 为工具响应预设 no-store，敏感输入与结果不应进入 HTTP 缓存。 */
@Component
public class ToolsNoStoreFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals("/tools") && !path.startsWith("/tools/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 在后续处理前设置，使经过此过滤器的成功与失败响应采用相同缓存策略。
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
