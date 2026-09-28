package com.accessflow.config;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import com.accessflow.dto.ErrorResponse;
import com.accessflow.web.WebErrorPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Two security chains, because the API and the browser UI have different needs.
 *
 * Spring Security picks the first chain whose {@code securityMatcher} accepts the
 * request, evaluating the beans in {@link Ordered} order, so the API chain is
 * declared first and claims {@code /api/**} only. Everything else falls through
 * to the UI chain.
 *
 * <h2>API chain (order 1, /api/**)</h2>
 * Stateless HTTP Basic, CSRF off, no session. Every request authenticates
 * independently, so no session or CSRF token is needed and no token-signing
 * secret is introduced.
 *
 * <pre>
 *   POST /api/auth/login                 public
 *   POST /api/users                      public registration, always EMPLOYEE
 *   GET  /api/users                      MANAGER, IT_ADMIN, SUPER_ADMIN
 *   GET  /api/users/** (single lookups)  any authenticated user
 *   GET  /api/access-requests            MANAGER, IT_ADMIN, SUPER_ADMIN
 *   GET  /api/access-requests/status/*   MANAGER, IT_ADMIN, SUPER_ADMIN
 *   GET  /api/access-requests/page       MANAGER, IT_ADMIN, SUPER_ADMIN
 *   /api/access-requests/**              any authenticated user
 *   anything else under /api             denied
 * </pre>
 *
 * <h2>UI chain (order 2, everything else)</h2>
 * Session cookie plus form login, CSRF left on. Reuses the same
 * UserDetailsService, PasswordEncoder and role authorities as the API chain; the
 * only difference is how the caller proves who they are. A browser posting to an
 * API route therefore cannot borrow the UI session: the API chain never looks at
 * the cookie, so a UI session is not an API credential.
 *
 * <pre>
 *   GET  /login, /css/**, /error          public
 *   GET  /requests/review                 MANAGER, IT_ADMIN, SUPER_ADMIN
 *   /, /requests, /requests/**            any authenticated user
 *   anything else                         denied
 * </pre>
 *
 * The service layer enforces the same rules again for every workflow action, so
 * a mistake in this configuration is not the only thing standing between an
 * employee and a reviewer's screen.
 */
@Configuration
public class SecurityConfig {

    /**
     * The roles allowed to review. Mirrors REVIEWER_ROLES in AccessRequestService,
     * which is the second gate; this list is the first.
     */
    private static final String[] REVIEWER_ROLES = {"MANAGER", "IT_ADMIN", "SUPER_ADMIN"};

    /**
     * The view name for the shared error page. The same name Spring Boot's own
     * error handling uses, so a refusal from this chain and a failure inside a
     * controller render one template.
     */
    private static final String ERROR_VIEW_NAME = "error";

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      AuthenticationEntryPoint authenticationEntryPoint,
                                                      AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users").hasAnyRole(REVIEWER_ROLES)
                        .requestMatchers("/api/users/**").authenticated()
                        // Reading every request, the review queue and a page of it is
                        // a reviewer job. /mine, /page's siblings /{id} and the
                        // transitions are open to any signed-in user; the service and
                        // the workflow rules decide what they may do with a request
                        // they can see. Listed before the /** rule below, which
                        // would otherwise claim it.
                        .requestMatchers(HttpMethod.GET, "/api/access-requests",
                                "/api/access-requests/status/*",
                                "/api/access-requests/page").hasAnyRole(REVIEWER_ROLES)
                        .requestMatchers("/api/access-requests/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /**
     * The browser chain. CSRF protection is deliberately NOT disabled: a session
     * cookie is attached by the browser automatically, so every state-changing
     * form has to prove it came from a page this application rendered.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http,
                                                      List<ViewResolver> viewResolvers,
                                                      WebErrorPage errorPage) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // The servlet error dispatch has to stay reachable, otherwise
                        // an unmapped URL cannot render its own 404.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/login", "/css/**").permitAll()
                        .requestMatchers("/requests/review").hasAnyRole(REVIEWER_ROLES)
                        .requestMatchers("/", "/requests", "/requests/**").authenticated()
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        .loginPage("/login")
                        // The email address is the login identifier, so the form
                        // names the field "email" rather than the default
                        // "username".
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                response.sendRedirect(request.getContextPath() + "/login"))
                        .accessDeniedHandler(renderErrorPage(viewResolvers, errorPage, HttpStatus.FORBIDDEN,
                                "You do not have permission to perform this action")));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Built from the UserDetailsService and PasswordEncoder beans by Spring
     * Security, so no provider has to be assembled by hand. Shared by both
     * chains, so the UI and the API accept exactly the same credentials.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Answers 401 and 403 with the same ErrorResponse body as every other
     * failure, so a client never has to parse two different error formats.
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) ->
                writeError(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                        "Authentication required");
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, deniedException) ->
                writeError(objectMapper, request, response, HttpStatus.FORBIDDEN,
                        "You do not have permission to perform this action");
    }

    private void writeError(ObjectMapper objectMapper, HttpServletRequest request,
                            HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(status.value(), status.getReasonPhrase(), message,
                        request.getRequestURI()));
    }

    /**
     * Renders the shared error page for a refusal decided inside the filter
     * chain, so it looks the same as one raised by a controller.
     *
     * The chain runs outside the DispatcherServlet, which means @ControllerAdvice
     * does not apply and the view has to be resolved and rendered here. "error" is
     * the view name Spring Boot's own error handling uses, so a chain refusal and
     * a Boot error page end up in the same template.
     *
     * Rendered rather than delegated to the container error dispatch on purpose:
     * rendering it here means the same bytes are produced in production and in
     * MockMvc, which a container dispatch would not guarantee.
     */
    private AccessDeniedHandler renderErrorPage(List<ViewResolver> viewResolvers, WebErrorPage errorPage,
                                                HttpStatus status, String message) {
        return (request, response, deniedException) -> {
            response.setStatus(status.value());
            response.setContentType(MediaType.TEXT_HTML_VALUE);

            View view = null;
            for (ViewResolver resolver : viewResolvers) {
                try {
                    view = resolver.resolveViewName(ERROR_VIEW_NAME, Locale.ENGLISH);
                } catch (Exception ex) {
                    throw new IOException("Could not resolve the error view", ex);
                }
                if (view != null) {
                    break;
                }
            }

            if (view == null) {
                // No view could be resolved, so a minimal document is the only
                // thing that can be written. Never the exception: it can quote a
                // denied URL.
                response.getWriter().write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                        + "<title>" + status.value() + " " + status.getReasonPhrase() + "</title></head><body><h1>"
                        + status.value() + " " + status.getReasonPhrase() + "</h1><p>" + message
                        + "</p></body></html>");
                return;
            }

            try {
                view.render(errorPage.attributes(status, message, request), request, response);
            } catch (Exception ex) {
                throw new IOException("Could not render the error page for " + request.getRequestURI(), ex);
            }
        };
    }
}
