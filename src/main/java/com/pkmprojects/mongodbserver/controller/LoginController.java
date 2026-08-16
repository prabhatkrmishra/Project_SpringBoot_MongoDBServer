package com.pkmprojects.mongodbserver.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the custom form-login page declared in {@code SecurityConfig}.
 * Spring Security handles the POST to {@code /login} itself; this controller
 * only renders the page.
 */
@Controller
public class LoginController {

    /**
     * Renders the login page (the POST itself is handled by Spring Security).
     */
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
}
