package com.accessflow.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

/**
 * Builds the one error page every failure ends up on.
 *
 * Three places need it and none of them can share a single class: a controller
 * failure arrives as an exception a {@code @ControllerAdvice} can handle, an
 * unmatched URL arrives with no handler at all, and a refusal decided inside the
 * security filter chain happens before the DispatcherServlet exists. They render
 * the same model attribute names, so the one {@code error.html} template serves
 * all three, and a user is not shown three different-looking error pages.
 *
 * The attribute names are Spring Boot's own error attributes, so this page is
 * also what a container error dispatch would fill in.
 */
@Component
public class WebErrorPage {

    /**
     * The view name. The same name Boot uses for its own error page, so the two
     * do not drift apart.
     */
    public static final String VIEW_NAME = "error";

    /**
     * A page for a failure a controller raised. Returns the view to render and
     * writes the status onto the response, because the status of a ModelAndView
     * returned from an exception handler is not applied on its own.
     */
    public ModelAndView forStatus(HttpStatus status, String message, HttpServletRequest request,
                                  HttpServletResponse response) {
        response.setStatus(status.value());

        return new ModelAndView(VIEW_NAME, attributes(status, message, request));
    }

    /**
     * The model for the error page, for a caller that renders the view itself
     * instead of returning it to the DispatcherServlet.
     */
    public Map<String, Object> attributes(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("status", status.value());
        model.put("error", status.getReasonPhrase());
        model.put("message", message);
        model.put("path", request.getRequestURI());

        return model;
    }
}
