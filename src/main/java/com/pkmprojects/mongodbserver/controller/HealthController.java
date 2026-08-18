package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.service.HealthService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Health dashboard: MongoDB server reachability and metrics (read-only).
 */
@Controller
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public String health(Model model) {
        model.addAttribute("health", healthService.getHealth());
        return "health";
    }
}
