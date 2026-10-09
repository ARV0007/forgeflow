package com.forgeflow.shared.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    @Bean(destroyMethod = "")
    SpanReporter spanReporter(@Value("${forgeflow.tracing.zipkin-url:}") String zipkinUrl,
                              @Value("${spring.application.name:forgeflow}") String serviceName) {
        if (zipkinUrl.isBlank()) {
            return SpanReporter.NOOP;
        }
        log.info("Reporting spans to Zipkin at {}", zipkinUrl);
        return new ZipkinReporter(zipkinUrl, serviceName, 1000);
    }

    @Bean
    Tracer tracer(SpanReporter reporter) {
        return new Tracer(reporter);
    }

    /** Outermost filter of all, ahead of Spring Security's chain. */
    @Bean
    FilterRegistrationBean<TracingFilter> tracingFilter(Tracer tracer) {
        FilterRegistrationBean<TracingFilter> reg = new FilterRegistrationBean<>(new TracingFilter(tracer));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
