package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for the paginated audit-trail view.
 */
@WebMvcTest(ActivityController.class)
@Import({SecurityConfig.class, ActivityControllerTest.SecurityTestConfig.class})
class ActivityControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogRepository auditLogRepository;

    @Test
    void activityPageRendersWithPagination() throws Exception {
        AuditEvent event = new AuditEvent(AuditEvent.PROVISION, "myapp", "appuser", "admin", NOW);
        Page<AuditEvent> page = new PageImpl<>(List.of(event), PageRequest.of(0, ActivityController.PAGE_SIZE), 1);
        when(auditLogRepository.findAll(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/activity").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("activity"))
                .andExpect(model().attributeExists("events", "page", "totalPages", "totalCount", "hasPrev", "hasNext"))
                .andExpect(model().attribute("page", 1))
                .andExpect(content().string(containsString("myapp")));
    }

    @Test
    void activityPageRendersForNonAdminReader() throws Exception {
        Page<AuditEvent> empty = new PageImpl<>(List.of(), PageRequest.of(0, ActivityController.PAGE_SIZE), 0);
        when(auditLogRepository.findAll(any(Pageable.class))).thenReturn(empty);

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

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin");
        }
    }
}
