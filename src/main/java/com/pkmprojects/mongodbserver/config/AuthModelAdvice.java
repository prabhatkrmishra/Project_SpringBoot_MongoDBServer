package com.pkmprojects.mongodbserver.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the current principal to every view model: {@code username} and
 * {@code canWrite} (has ADMIN role). Used by templates for display gating only -
 * the real authorization is enforced server-side by Spring Security.
 */
@ControllerAdvice
public class AuthModelAdvice {

    /**
     * @return the authenticated principal's username for every view, or
     * {@code null} when anonymous
     */
    @ModelAttribute("username")
    public String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    /**
     * @return {@code true} when the current principal holds the ADMIN role.
     * Display gating only - the actual authorization is enforced by
     * Spring Security.
     */
    @ModelAttribute("canWrite")
    public boolean canWrite() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
