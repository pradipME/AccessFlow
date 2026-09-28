package com.accessflow.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The two pages that belong to signing in rather than to the workflow: the login
 * form and the landing page.
 *
 * POST /login and POST /logout are handled by Spring Security's own filters, not
 * by a controller. The identity shown on the home page comes from the
 * {@code currentUser} model attribute that WebViewAdvice supplies, which is read
 * off the authentication.
 */
@Controller
public class WebAuthController {

    /**
     * Renders the sign-in form. Registered as a page rather than left to the
     * default so the form can be a real Thymeleaf template with a CSRF token, and
     * so the failure redirect (?error) lands on the same page.
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * The landing page. It is authenticated rather than public so that an
     * anonymous visitor is sent to the sign-in form instead of seeing an empty
     * shell.
     */
    @GetMapping("/")
    public String home() {
        return "home";
    }
}
