package com.test.blog.tools.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/** 禁止 JSON 数字、布尔值被自动转成字符串，防止密钥或正文被隐式改写。 */
@Configuration(proxyBeanMethods = false)
public class ToolsJsonConfig {
    @Bean
    JsonMapperBuilderCustomizer strictStringInputs() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
