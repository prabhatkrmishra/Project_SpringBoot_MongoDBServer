package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.service.ExplorationService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Database detail, password reset, and deletion (admin-only writes).
 */
@Controller
public class DatabaseController {

    private final ProvisioningService provisioningService;
    private final ExplorationService explorationService;

    public DatabaseController(ProvisioningService provisioningService, ExplorationService explorationService) {
        this.provisioningService = provisioningService;
        this.explorationService = explorationService;
    }

    /**
     * Renders the database detail page with its collections.
     */
    @GetMapping("/databases/{dbName}")
    public String detail(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(dbName));
        model.addAttribute("collections", explorationService.listCollections(dbName));
        if (!model.containsAttribute("resetForm")) {
            model.addAttribute("resetForm", new ResetPasswordForm(""));
        }
        return "database";
    }

    /**
     * Renders the password-reset confirmation page (admin only).
     */
    @GetMapping("/databases/{dbName}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetForm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(dbName));
        if (!model.containsAttribute("resetForm")) {
            model.addAttribute("resetForm", new ResetPasswordForm(""));
        }
        return "reset-password";
    }

    /**
     * Rotates the provisioned user's password (admin only). On success redirects
     * to the detail page with the show-once connection string.
     */
    @PostMapping("/databases/{dbName}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetPassword(@PathVariable String dbName,
                                @Valid @ModelAttribute("resetForm") ResetPasswordForm form,
                                BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("database", provisioningService.getDatabase(dbName));
            return "reset-password";
        }
        DatabaseInfo updated = provisioningService.resetPassword(dbName, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Password reset for database '" + dbName + "'");
        redirectAttributes.addFlashAttribute("newCredentials", updated);
        return "redirect:/databases/" + dbName;
    }

    /**
     * Renders the delete-confirmation page (admin only).
     */
    @GetMapping("/databases/{dbName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteConfirm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(dbName));
        return "delete-confirm";
    }

    /**
     * Deletes the database, its user, and its metadata (admin only).
     */
    @PostMapping("/databases/{dbName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable String dbName, RedirectAttributes redirectAttributes) {
        java.util.List<String> warnings = provisioningService.delete(dbName);
        if (warnings.isEmpty()) {
            redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + dbName + "' deleted");
        } else {
            // The Mongo database and metadata are gone, but RESTHeart cleanup only
            // partially succeeded — leftover users/ACL entries there can otherwise
            // silently corrupt a future re-provision of this same database name.
            redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + dbName + "' deleted");
            redirectAttributes.addFlashAttribute("flashWarning",
                    "RESTHeart cleanup did not fully complete: " + String.join("; ", warnings)
                            + ". Check /restheart/users and /restheart/acl before reusing this name.");
        }
        return "redirect:/";
    }
}
