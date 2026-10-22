package com.ohcp.cropagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发期跨域放行。
 *
 * 交付时前端构建产物会拷进 static 目录走同源，这段配置用不上；
 * 但前后端并行开发时前端跑在 5173，不放行连不上。
 * 只放行本机端口，不用通配符——免得演示时被问"你这不是全开了吗"。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173", "http://127.0.0.1:5173",
                        // 5173 被其他项目占用时，Vite 会自动退到 5174
                        "http://localhost:5174", "http://127.0.0.1:5174",
                        "http://localhost:3000", "http://127.0.0.1:3000")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
