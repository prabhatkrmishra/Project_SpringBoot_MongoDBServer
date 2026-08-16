package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Full, paginated view of the admin activity audit trail (read-only).
 */
@Controller
public class ActivityController {

    /**
     * Page size for the audit-trail listing.
     */
    static final int PAGE_SIZE = 50;

    private final AuditLogRepository auditLogRepository;

    public ActivityController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Renders one page of the audit trail, newest first. Out-of-range pages
     * clamp to the first page.
     */
    @GetMapping("/activity")
    public String activity(@RequestParam(name = "page", defaultValue = "1") int page, Model model) {
        int safePage = Math.max(page, 1);
        Page<AuditEvent> events = auditLogRepository.findAll(
                PageRequest.of(safePage - 1, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "performedAt")));
        model.addAttribute("events", events.getContent());
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", Math.max(events.getTotalPages(), 1));
        model.addAttribute("totalCount", events.getTotalElements());
        model.addAttribute("hasPrev", events.hasPrevious());
        model.addAttribute("hasNext", events.hasNext());
        return "activity";
    }
}
