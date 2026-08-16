package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Dashboard: database list + provisioning form + recent admin activity.
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
     * Renders the dashboard: database list, provisioning form, recent activity.
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        prepareDashboard(model);
        return "index";
    }

    /**
     * Provisions a new database. On success redirects to the new database's
     * detail page with the show-once credentials in a flash attribute.
     */
    @PostMapping("/databases")
    public String provision(@Valid @ModelAttribute("form") CreateDatabaseForm form,
                            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            prepareDashboard(model);
            return "index";
        }
        DatabaseInfo created = provisioningService.provision(form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + created.dbName() + "' provisioned");
        redirectAttributes.addFlashAttribute("newCredentials", created);
        return "redirect:/databases/" + created.dbName();
    }

    private void prepareDashboard(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateDatabaseForm("", "", ""));
        }
        model.addAttribute("databases", provisioningService.listDatabases());
        model.addAttribute("recentActivity", auditLogRepository.findTop10ByOrderByPerformedAtDesc());
    }
}
