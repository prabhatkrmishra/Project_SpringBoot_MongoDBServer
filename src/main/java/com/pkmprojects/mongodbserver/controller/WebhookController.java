package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.WebhookForm;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.service.WebhookService;
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

import java.util.List;

/**
 * Webhook endpoint management (admin only).
 */
@Controller
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Renders the webhook management page with the add form.
     */
    @GetMapping("/webhooks")
    @PreAuthorize("hasRole('ADMIN')")
    public String webhooks(Model model) {
        model.addAttribute("webhooks", webhookService.listWebhooks());
        model.addAttribute("eventTypes", AuditEvent.ALL_TYPES);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new WebhookForm("", "", "", List.of()));
        }
        return "webhooks";
    }

    /**
     * Creates a webhook endpoint. On success redirects to the management page.
     */
    @PostMapping("/webhooks")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@Valid @ModelAttribute("form") WebhookForm form,
                         BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("webhooks", webhookService.listWebhooks());
            model.addAttribute("eventTypes", AuditEvent.ALL_TYPES);
            return "webhooks";
        }
        webhookService.createWebhook(form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Webhook '" + form.name() + "' created");
        return "redirect:/webhooks";
    }

    /**
     * Enables or disables a webhook endpoint.
     */
    @PostMapping("/webhooks/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public String toggle(@PathVariable String id, RedirectAttributes redirectAttributes) {
        webhookService.toggleWebhook(id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Webhook updated");
        return "redirect:/webhooks";
    }

    /**
     * Deletes a webhook endpoint.
     */
    @PostMapping("/webhooks/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
        webhookService.deleteWebhook(id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Webhook deleted");
        return "redirect:/webhooks";
    }
}