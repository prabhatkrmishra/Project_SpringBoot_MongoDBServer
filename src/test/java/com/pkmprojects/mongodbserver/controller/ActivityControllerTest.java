package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for the paginated, filterable audit-trail view.
 */
@WebMvcTest(ActivityController.class)
@Import({SecurityConfig.class, ActivityControllerTest.SecurityTestConfig.class})
class ActivityControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    @Test
    void activityPageRendersWithPagination() throws Exception {
        AuditEvent event = new AuditEvent(AuditEvent.PROVISION, "myapp", "appuser", "admin", NOW);
        when(mongoTemplate.count(any(Query.class), eq(AuditEvent.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of(event));

        mockMvc.perform(get("/activity").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("activity"))
                .andExpect(model().attributeExists("events", "page", "totalPages", "totalCount", "hasPrev", "hasNext"))
                .andExpect(model().attribute("page", 1))
                .andExpect(content().string(containsString("myapp")));
    }

    @Test
    void activityPageRendersForNonAdminReader() throws Exception {
        when(mongoTemplate.count(any(Query.class), eq(AuditEvent.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of());

        mockMvc.perform(get("/activity").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("activity"));
    }

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/activity"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void filteredByEventTypeReturnsMatchingEvents() throws Exception {
        AuditEvent event = new AuditEvent(AuditEvent.DELETE, "myapp", "appuser", "admin", NOW);
        when(mongoTemplate.count(any(Query.class), eq(AuditEvent.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of(event));

        mockMvc.perform(get("/activity").param("eventType", "DELETE").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("activity"))
                .andExpect(model().attribute("totalCount", 1L))
                .andExpect(content().string(containsString("DELETE")));
    }

    @Test
    void filteredByDbNameReturnsMatchingEvents() throws Exception {
        AuditEvent event = new AuditEvent(AuditEvent.PROVISION, "myapp", "appuser", "admin", NOW);
        when(mongoTemplate.count(any(Query.class), eq(AuditEvent.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of(event));

        mockMvc.perform(get("/activity").param("dbName", "myapp").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("activity"))
                .andExpect(model().attribute("totalCount", 1L));
    }

    @Test
    void filterStatePersistedInFormInputs() throws Exception {
        when(mongoTemplate.count(any(Query.class), eq(AuditEvent.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of());

        mockMvc.perform(get("/activity")
                        .param("eventType", "PROVISION")
                        .param("dbName", "myapp")
                        .param("userName", "appuser")
                        .param("performedBy", "admin")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"PROVISION\"")))
                .andExpect(content().string(containsString("value=\"myapp\"")))
                .andExpect(content().string(containsString("value=\"appuser\"")))
                .andExpect(content().string(containsString("value=\"admin\"")));
    }

    @Test
    void emptyFiltersShowAllEvents() throws Exception {
        when(mongoTemplate.count(any(Query.class), eq(AuditEvent.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of());

        mockMvc.perform(get("/activity").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No matching activity")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin");
        }
    }
}
