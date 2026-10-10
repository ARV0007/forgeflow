package com.forgeflow.gateway;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
class GatewayConfig {

    @Bean
    RouteTable routeTable(GatewayProperties props) {
        return new RouteTable(props.routes(), props.upstreams(), props.previewDomain(), props.previewUpstream());
    }

    @Bean
    EdgeAuth edgeAuth(GatewayProperties props) {
        return new EdgeAuth(props.jwtSecret());
    }

    @Bean
    FilterRegistrationBean<ProxyFilter> proxy(RouteTable routes, EdgeAuth auth) {
        FilterRegistrationBean<ProxyFilter> reg = new FilterRegistrationBean<>(new ProxyFilter(routes, auth));
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}
