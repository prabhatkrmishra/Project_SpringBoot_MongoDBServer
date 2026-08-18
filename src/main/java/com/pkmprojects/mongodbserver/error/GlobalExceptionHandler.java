package com.pkmprojects.mongodbserver.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps domain exceptions to the shared error view with a proper HTTP status.
 * Driver-level failures are logged with full context but never shown raw.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps a missing database/collection to HTTP 404.
     */
    @ExceptionHandler(DatabaseNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(DatabaseNotFoundException ex, Model model) {
        return errorView(model, HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage());
    }

    /**
     * Maps an invalid name/password to HTTP 400.
     */
    @ExceptionHandler(NameNotAllowedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(NameNotAllowedException ex, Model model) {
        return errorView(model, HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage());
    }

    /**
     * Maps a duplicate database/collection to HTTP 409.
     */
    @ExceptionHandler(DatabaseAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String conflict(DatabaseAlreadyExistsException ex, Model model) {
        return errorView(model, HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage());
    }

    /**
     * Maps a request query/path value to the wrong type (e.g. {@code ?page=abc})
     * to HTTP 400 instead of letting the catch-all turn it into a 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String typeMismatch(MethodArgumentTypeMismatchException ex, Model model) {
        log.debug("Bad argument for [{}] in request", ex.getName(), ex);
        return errorView(model, HttpStatus.BAD_REQUEST.value(), "Bad Request",
                "Invalid value for parameter \"" + ex.getName() + "\"");
    }

    /**
     * Maps an unsupported HTTP method (e.g. DELETE on a read-only route) to
     * HTTP 405 instead of a 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public String wrongMethod(HttpRequestMethodNotSupportedException ex, Model model) {
        log.debug("Method not supported for {}", ex.getMethod(), ex);
        return errorView(model, HttpStatus.METHOD_NOT_ALLOWED.value(), "Method Not Allowed",
                "HTTP method \"" + ex.getMethod() + "\" is not supported for this resource");
    }

    /**
     * Maps failed form binding to HTTP 400.
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String bindFailure(BindException ex, Model model) {
        log.debug("Bad request because of invalid binding", ex);
        return errorView(model, HttpStatus.BAD_REQUEST.value(), "Bad Request",
                "The submitted values could not be bound to the request");
    }

    /**
     * Maps a missing webhook endpoint to HTTP 404.
     */
    @ExceptionHandler(WebhookNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String webhookNotFound(WebhookNotFoundException ex, Model model) {
        return errorView(model, HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage());
    }

    /**
     * Maps a failed MongoDB driver operation to HTTP 500 with a generic message;
     * the full stack trace goes to the log, never to the browser.
     */
    @ExceptionHandler(ProvisioningException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String provisioning(ProvisioningException ex, Model model) {
        log.error("Provisioning operation failed", ex);
        return errorView(model, HttpStatus.INTERNAL_SERVER_ERROR.value(), "Server Error",
                "An internal error occurred while managing databases");
    }

    /**
     * Missing static assets (e.g. {@code /favicon.ico}) are routine browser noise,
     * not errors: serve a clean 404 and log at debug instead of ERROR.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String noResource(NoResourceFoundException ex, Model model) {
        log.debug("No static resource {}", ex.getResourcePath());
        return errorView(model, HttpStatus.NOT_FOUND.value(), "Not Found", "Resource not found");
    }

    /**
     * The client closed an async connection (e.g. an SSE stream listener
     * navigated away or closed the tab). The response is gone, so there is
     * nothing to send - this is day-one behaviour, not an error. Swallowing it
     * also prevents the catch-all from trying to render an error view onto the
     * already-dead socket (which would itself throw and cascade).
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void requestNotUsable(AsyncRequestNotUsableException ex) {
        log.debug("Async response no longer usable (client disconnected): {}", ex.getMessage());
    }

    /**
     * Catch-all for HTTP 500. Security exceptions are rethrown so the security
     * filter chain produces the correct 401/403 response instead of an error page.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Exception ex, Model model) throws Exception {
        // Security exceptions must propagate to the security filter chain so they
        // produce the correct 401/403 response instead of a 500 error page.
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("Unexpected error", ex);
        return errorView(model, HttpStatus.INTERNAL_SERVER_ERROR.value(), "Server Error",
                "An unexpected error occurred");
    }

    /**
     * Populates the shared {@code error} template with status details.
     */
    private String errorView(Model model, int status, String error, String message) {
        model.addAttribute("status", status);
        model.addAttribute("error", error);
        model.addAttribute("message", message);
        return "error";
    }
}
