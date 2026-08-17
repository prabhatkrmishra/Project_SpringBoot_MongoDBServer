package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.CreateAclEntryForm;
import com.pkmprojects.mongodbserver.dto.CreateRestheartUserForm;
import com.pkmprojects.mongodbserver.dto.ResetRestheartPasswordForm;
import com.pkmprojects.mongodbserver.service.RestheartService;
import jakarta.validation.Valid;
import org.bson.Document;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Admin UI for managing RESTHeart users ({@code restheart.users}) and
 * ACL rules ({@code restheart.acl}). These control which HTTP Basic
 * credentials RESTHeart accepts and which URL patterns they can access.
 */
@Controller
@RequestMapping("/restheart")
@PreAuthorize("hasRole('ADMIN')")
public class RestheartController {

    private final RestheartService restheartService;

    public RestheartController(RestheartService restheartService) {
        this.restheartService = restheartService;
    }

    // ─── Users ────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public String listUsers(Model model) {
        List<Document> users = restheartService.listUsers();
        model.addAttribute("users", users);
        model.addAttribute("createForm", new CreateRestheartUserForm("", "", "user"));
        return "restheart-users";
    }

    @PostMapping("/users")
    public String createUser(@Valid @ModelAttribute("createForm") CreateRestheartUserForm form,
                             BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("users", restheartService.listUsers());
            return "restheart-users";
        }
        restheartService.createUser(form.userName(), form.password(), form.parsedRoles());
        redirectAttributes.addFlashAttribute("flashSuccess", "RESTHeart user '" + form.userName() + "' created");
        return "redirect:/restheart/users";
    }

    @GetMapping("/users/{id}/reset")
    public String resetPasswordForm(@PathVariable String id, Model model) {
        Document user = restheartService.findUser(id);
        if (user == null) {
            model.addAttribute("flashError", "RESTHeart user '" + id + "' not found");
            return "redirect:/restheart/users";
        }
        model.addAttribute("user", user);
        model.addAttribute("resetForm", new ResetRestheartPasswordForm(""));
        return "restheart-reset-password";
    }

    @PostMapping("/users/{id}/reset")
    public String resetPassword(@PathVariable String id,
                                @Valid @ModelAttribute("resetForm") ResetRestheartPasswordForm form,
                                BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("user", restheartService.findUser(id));
            return "restheart-reset-password";
        }
        restheartService.resetPassword(id, form.password());
        redirectAttributes.addFlashAttribute("flashSuccess", "Password reset for RESTHeart user '" + id + "'");
        return "redirect:/restheart/users";
    }

    @PostMapping("/users/{id}/roles")
    public String updateRoles(@PathVariable String id,
                              @RequestParam("roles") String rolesCsv,
                              RedirectAttributes redirectAttributes) {
        List<String> roles = java.util.Arrays.stream(rolesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        restheartService.updateRoles(id, roles);
        redirectAttributes.addFlashAttribute("flashSuccess", "Roles updated for '" + id + "'");
        return "redirect:/restheart/users";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable String id, RedirectAttributes redirectAttributes) {
        restheartService.deleteUser(id);
        redirectAttributes.addFlashAttribute("flashSuccess", "RESTHeart user '" + id + "' deleted");
        return "redirect:/restheart/users";
    }

    // ─── ACL ──────────────────────────────────────────────────────────────────

    @GetMapping("/acl")
    public String listAcl(Model model) {
        List<Document> acl = restheartService.listAcl();
        model.addAttribute("acl", acl);
        model.addAttribute("aclForm", new CreateAclEntryForm("", "path-prefix('/')", "user", 100, false));
        return "restheart-acl";
    }

    @PostMapping("/acl")
    public String upsertAcl(@Valid @ModelAttribute("aclForm") CreateAclEntryForm form,
                            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("acl", restheartService.listAcl());
            return "restheart-acl";
        }
        restheartService.upsertAclEntry(
                form.ruleId(), form.predicate(), form.parsedRoles(),
                form.priority(), form.allowManagementRequests());
        redirectAttributes.addFlashAttribute("flashSuccess", "ACL rule '" + form.ruleId() + "' saved");
        return "redirect:/restheart/acl";
    }

    @PostMapping("/acl/{id}/delete")
    public String deleteAcl(@PathVariable String id, RedirectAttributes redirectAttributes) {
        restheartService.deleteAclEntry(id);
        redirectAttributes.addFlashAttribute("flashSuccess", "ACL rule '" + id + "' deleted");
        return "redirect:/restheart/acl";
    }
}
