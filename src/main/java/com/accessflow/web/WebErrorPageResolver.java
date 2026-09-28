package com.accessflow.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The error page for a URL that matches no controller.
 *
 * {@link WebViewAdvice} cannot cover this case. A {@code @ControllerAdvice} is
 * matched against the controller method that raised the exception, and a request
 * for a page that does not exist is answered by the resource handler, which is
 * not one. Left to itself, Spring MVC would apply the status and nothing else,
 * so the browser would get a blank 404 from the container's error dispatch
 * instead of the page every other failure shows.
 *
 * Handles only these two exceptions and returns null for anything else, which
 * passes the exception to the next resolver in the chain. It is placed first by
 * {@link WebViewConfiguration}.
 */
@Component
public class WebErrorPageResolver implements HandlerExceptionResolver {

    private final WebErrorPage errorPage;

    public WebErrorPageResolver(WebErrorPage errorPage) {
        this.errorPage = errorPage;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        boolean nothingToRender = ex instanceof NoHandlerFoundException
                || ex instanceof NoResourceFoundException;

        if (!nothingToRender) {
            return null;
        }

        return errorPage.forStatus(HttpStatus.NOT_FOUND, "We could not find that page", request, response);
    }
}
