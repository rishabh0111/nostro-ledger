package io.nostro.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every path of this API carries {@code /v1}, added once here for every handler in {@code io.nostro}
 * rather than spelt on each mapping. What is not ours — the generated OpenAPI document and its UI,
 * Boot's {@code /error} — stays where its library put it.
 */
@Configuration(proxyBeanMethods = false)
public class ApiVersion implements WebMvcConfigurer {

    public static final String V1 = "/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(V1, HandlerTypePredicate.forBasePackage("io.nostro"));
    }
}
