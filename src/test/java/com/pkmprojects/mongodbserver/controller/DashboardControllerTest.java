package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for the dashboard: rendering, provisioning, and validation
 * error paths.
 */
@WebMvcTest({DashboardController.class, LoginController.class})
@Import({SecurityConfig.class, DashboardControllerTest.SecurityTestConfig.class})
class DashboardControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProvisioningService provisioningService;

    @MockitoBean
    private AuditLogRepository auditLogRepository;

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginPageRendersWithCsrfToken() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void loginAsAdminSucceeds() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "admin")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void dashboardListsDatabasesAndActivity() throws Exception {
        when(provisioningService.listDatabases()).thenReturn(List.of(
                new DatabaseInfo("myapp", "appuser", List.of("readWrite:myapp"), 2, NOW, NOW, null, true, null, "http://localhost:9814", null),
                new DatabaseInfo("external", null, List.of(), 0, null, null, null, false, null, "http://localhost:9814", null)));
        when(auditLogRepository.findTop10ByOrderByPerformedAtDesc()).thenReturn(List.of(
                new AuditEvent(AuditEvent.PROVISION, "myapp", "appuser", "admin", NOW)));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("databases", "recentActivity"))
                .andExpect(content().string(containsString("myapp")))
                .andExpect(content().string(containsString("external")))
                .andExpect(content().string(containsString("Recent activity")));
    }

    @Test
    void provisionAsAdminCreatesDatabaseAndRedirects() throws Exception {
        when(provisioningService.provision(any(CreateDatabaseForm.class))).thenReturn(
                new DatabaseInfo("myapp", "appuser", List.of("readWrite:myapp"), 1, NOW, NOW, null, true,
                        "RESTHEART_URL=http://localhost:9814\nDB_USER=appuser\nDB_PASS=generatedPass123\nMONGODB_DB=myapp",
                        "http://localhost:9814", null));

        mockMvc.perform(post("/databases")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("dbName", "myapp")
                        .param("userName", "appuser")
                        .param("password", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp"));

        verify(provisioningService).provision(any(CreateDatabaseForm.class));
    }

    @Test
    void provisionWithInvalidFormRerendersDashboard() throws Exception {
        mockMvc.perform(post("/databases")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("dbName", "bad name!")
                        .param("userName", "appuser"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeHasFieldErrors("form", "dbName"));

        verify(provisioningService, never()).provision(any());
    }

    @Test
    void nonAdminCannotProvision() throws Exception {
        mockMvc.perform(post("/databases")
                        .with(user("bob").roles("USER"))
                        .with(csrf())
                        .param("dbName", "myapp")
                        .param("userName", "appuser"))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).provision(any());
    }

    @Test
    void provisionWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/databases")
                        .with(user("admin").roles("ADMIN"))
                        .param("dbName", "myapp")
                        .param("userName", "appuser"))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).provision(any());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin");
        }
    }
}
