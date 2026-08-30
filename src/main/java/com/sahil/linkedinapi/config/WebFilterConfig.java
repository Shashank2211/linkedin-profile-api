package com.sahil.linkedinapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filter order matters and is stated explicitly rather than left to discovery:
 * request id first (so the other two can log with it), then authentication,
 * then rate limiting (no point metering a caller we are about to reject).
 *
 * <p>{@code RequestIdFilter} is a plain {@code @Component} with its own {@code @Order}
 * rather than a registration bean here. That is not a stylistic difference: filter
 * <em>beans</em> are picked up by {@code @WebMvcTest} slices, so the request-id plumbing is
 * exercised by the controller tests. The other two need constructor arguments, so they are
 * registered explicitly and stay out of the slice — which is also what keeps the controller
 * tests free of authentication setup.
 */
@Configuration
public class WebFilterConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(AppProperties props, ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new ApiKeyFilter(props.apiKeyList(), mapper));
        registration.setOrder(2);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(AppProperties props, ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new RateLimitFilter(props.rateLimit(), mapper));
        registration.setOrder(3);
        return registration;
    }
}
