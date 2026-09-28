package com.accessflow.web;

import com.accessflow.IntegrationTestSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a browser is shown when a page fails in a way nobody planned.
 *
 * The fault is raised by a stub controller rather than by a real page, because
 * the application is written not to have any: every page either works or reports
 * one of the named failures covered by the other UI tests. This covers the
 * catch-all, whose whole job is to never leak what went wrong.
 *
 * The stub lives in this package because WebViewAdvice is scoped to it. That
 * scoping is the design under test: the JSON advice must not answer a page, and
 * the page advice must not answer the API.
 *
 * MockMvc is built standalone and wired to the real template engine, so the
 * assertions are made against the bytes a browser would receive.
 */
@DisplayName("Phase 3 - the browser error page")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class WebErrorPageTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationContext applicationContext;

    private MockMvc stubMvc;

    @RestController
    static class FaultyController {

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("jdbc:hikari://db-internal:3306 refused the pool");
        }

        @GetMapping("/bad")
        String bad() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
        }
    }

    @BeforeEach
    void buildStandaloneMvc() {
        SpringResourceTemplateResolver templates = new SpringResourceTemplateResolver();
        templates.setApplicationContext(applicationContext);
        templates.setPrefix("classpath:/templates/");
        templates.setSuffix(".html");
        templates.setTemplateMode(TemplateMode.HTML);
        templates.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);

        ThymeleafViewResolver views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");
        views.setContentType("text/html;charset=UTF-8");

        stubMvc = MockMvcBuilders.standaloneSetup(new FaultyController())
                .setControllerAdvice(new WebViewAdvice(new WebErrorPage()))
                .setViewResolvers(views)
                .build();
    }

    @Test
    @DisplayName("An unexpected fault is a plain 500 page, not a stack trace")
    void unexpectedFaultIsHidden() throws Exception {
        stubMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("500")))
                .andExpect(content().string(Matchers.containsString("An unexpected error occurred")))
                .andExpect(content().string(Matchers.containsString("href=\"/login\"")));
    }

    @Test
    @DisplayName("The page states that something went wrong and nothing about how")
    void noInternalDetailIsShown() throws Exception {
        String page = stubMvc.perform(get("/boom"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(page)
                .doesNotContain("jdbc:hikari")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.accessflow")
                .doesNotContain("Exception")
                .doesNotContain("org.springframework");
    }

    @Test
    @DisplayName("A database fault is reported the same way, without the constraint detail")
    void dataFaultIsHidden() throws Exception {
        String page = stubMvc.perform(get("/bad"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(page)
                .contains("An unexpected error occurred")
                .doesNotContain("unique constraint");
    }

    @Test
    @DisplayName("The page offers a way back into the application")
    void errorPageOffersAWayBack() throws Exception {
        String page = stubMvc.perform(get("/boom"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(page)
                .contains("href=\"/\"")
                .contains("href=\"/login\"")
                .contains("Back to the start");
    }

    @Test
    @DisplayName("An unknown page is a 404 page, not a 500")
    void unknownPageIsNotFoundRatherThanAFault() throws Exception {
        // A mistyped URL is a client mistake. It reaches the DispatcherServlet
        // because the chain let it through - /requests/abc matches the
        // /requests/** rule - and no handler claims it.
        //
        // NoResourceFoundException is what the servlet raises at that point, and
        // the catch-all above is declared for Exception, so it would match. Without
        // a handler of its own here, a broken link or a stale bookmark would be
        // answered with "500 An unexpected error occurred" and logged as a server
        // fault, sending an operator looking for a bug that does not exist.
        mockMvc.perform(get("/requests/not-a-number").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("404")));

        // A path the chain refuses outright never reaches the servlet at all, so it
        // stays a 403 from the chain and is not turned into a 404 by this.
        mockMvc.perform(get("/no/such/page").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isForbidden());
    }
}
