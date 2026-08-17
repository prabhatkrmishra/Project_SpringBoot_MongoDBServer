package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Dashboard: database list + recent admin activity.
 */
@Controller
public class DashboardController {

    private final ProvisioningService provisioningService;
    private final AuditLogRepository auditLogRepository;

    public DashboardController(ProvisioningService provisioningService, AuditLogRepository auditLogRepository) {
        this.provisioningService = provisioningService;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Renders the dashboard: database list and recent activity.
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("databases", provisioningService.listDatabases());
        model.addAttribute("recentActivity", auditLogRepository.findTop10ByOrderByPerformedAtDesc());
        return "index";
    }
}
