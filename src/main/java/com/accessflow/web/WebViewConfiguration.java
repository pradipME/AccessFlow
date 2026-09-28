package com.accessflow.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts {@link WebErrorPageResolver} at the front of the exception resolver
 * chain.
 *
 * Spring MVC builds its own resolvers and, for a servlet web application, does
 * not pick up a HandlerExceptionResolver bean on its own, so the insertion has to
 * be declared here. First is what makes the difference: the resolver answers
 * only for a URL that matches no controller, and leaves everything else to the
 * resolvers behind it.
 */
@Configuration
public class WebViewConfiguration implements WebMvcConfigurer {

    private final WebErrorPageResolver errorPageResolver;

    public WebViewConfiguration(WebErrorPageResolver errorPageResolver) {
        this.errorPageResolver = errorPageResolver;
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, errorPageResolver);
    }
}
