package com.pkmprojects.mongodbserver.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoginRateLimitFilter}: only POST /login is throttled,
 * excess attempts yield 429 + Retry-After, and everything else passes through.
 */
@ExtendWith(MockitoExtension.class)
class LoginRateLimitFilterTest {

    @Mock
    private LoginRateLimiter rateLimiter;

    @Mock
    private MockFilterChain filterChain;

    private final LoginRateLimitProperties properties = new LoginRateLimitProperties(5, Duration.ofMinutes(15), false);

    @Test
    void allowsLoginWhenUnderLimit() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = postLogin("bob");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LoginRateLimitFilter(rateLimiter, properties).doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void blocksWith429AndRetryAfterWhenLimitExceeded() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(false);

        MockHttpServletRequest request = postLogin("bob");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LoginRateLimitFilter(rateLimiter, properties).doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void passesNonLoginRequestsWithoutRateLimiting() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LoginRateLimitFilter(rateLimiter, properties).doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void ignoresSpoofedForwardedForHeaderByDefault() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = postLogin("bob");
        request.addHeader("X-Forwarded-For", "6.6.6.6");

        new LoginRateLimitFilter(rateLimiter, properties).doFilter(request, response(), filterChain);

        // the spoofed header must NOT become the client identity
        verify(rateLimiter).isAllowed("127.0.0.1:bob", 5, Duration.ofMinutes(15));
    }

    private MockHttpServletRequest postLogin(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setServletPath("/login");
        request.setRemoteAddr("127.0.0.1");
        request.addParameter("username", username);
        request.addParameter("password", "wrong");
        return request;
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }
}
