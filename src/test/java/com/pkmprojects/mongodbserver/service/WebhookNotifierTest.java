package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.repository.WebhookConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for webhook event fan-out and delivery.
 */
@ExtendWith(MockitoExtension.class)
class WebhookNotifierTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Mock
    private WebhookConfigRepository webhookConfigRepository;
    @Mock
    private HttpClient httpClient;
    @Mock
    private ExecutorService executor;

    private WebhookNotifier notifier;

    @BeforeEach
    void setUp() {
        // Run deliveries synchronously so tests observe the send call. Skip-tests
        // never submit, so the stub must be lenient.
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).submit(any(Runnable.class));
        notifier = new WebhookNotifier(webhookConfigRepository, httpClient, executor);
    }

    private WebhookConfig webhook(String name, String url, String secret, List<String> eventTypes, boolean enabled) {
        return new WebhookConfig(name, url, secret, eventTypes, enabled, NOW);
    }

    private AuditEventRecorded event() {
        return new AuditEventRecorded(
                new AuditEvent(AuditEvent.PROVISION, "myapp", "appuser", "admin", NOW));
    }

    private HttpResponse<String> response(int status) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return response;
    }

    private String bodyOf(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private java.util.concurrent.Flow.Subscription subscription;

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                subscription = s;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                java.nio.ByteBuffer copy = item.duplicate();
                byte[] chunk = new byte[copy.remaining()];
                copy.get(chunk);
                out.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.get(5, java.util.concurrent.TimeUnit.SECONDS);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void onAuditEventPostsMatchingWebhook() throws Exception {
        HttpResponse<String> ok = response(200);
        when(webhookConfigRepository.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null,
                        List.of(AuditEvent.PROVISION, AuditEvent.DELETE), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);

        notifier.onAuditEvent(event());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        assertThat(request.uri().toString()).isEqualTo("https://example.com/hooks/events");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(request.headers().firstValue("X-Webhook-Signature")).isEmpty();
        String body = bodyOf(request);
        assertThat(body).contains("\"eventType\":\"PROVISION\"");
        assertThat(body).contains("\"dbName\":\"myapp\"");
        assertThat(body).contains("\"userName\":\"appuser\"");
        assertThat(body).contains("\"performedBy\":\"admin\"");
    }

    @Test
    void onAuditEventSkipsWebhookWithNonMatchingEventType() {
        when(webhookConfigRepository.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null,
                        List.of(AuditEvent.DELETE), true)));

        notifier.onAuditEvent(event());

        verifyNoInteractions(httpClient);
    }

    @Test
    void onAuditEventSkipsDisabledWebhook() {
        when(webhookConfigRepository.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(AuditEvent.PROVISION), false)));

        notifier.onAuditEvent(event());

        verifyNoInteractions(httpClient);
    }

    @Test
    void webhookWithoutEventTypesReceivesAllEvents() throws Exception {
        HttpResponse<String> ok = response(200);
        when(webhookConfigRepository.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);

        notifier.onAuditEvent(event());

        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void secretWebhookSignsPayloadWithHmacSha256() throws Exception {
        HttpResponse<String> ok = response(200);
        when(webhookConfigRepository.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", "hunter2", List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);

        notifier.onAuditEvent(event());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        String body = bodyOf(request);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("hunter2".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(request.headers().firstValue("X-Webhook-Signature")).hasValue(expected);
    }

    @Test
    void transientFailureIsRetriedUpToThreeTimes() throws Exception {
        HttpResponse<String> failure = response(500);
        when(webhookConfigRepository.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(failure);

        notifier.onAuditEvent(event());

        verify(httpClient, times(WebhookNotifier.MAX_DELIVERY_ATTEMPTS))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void successAfterRetryStopsRetrying() throws Exception {
        HttpResponse<String> failure = response(500);
        HttpResponse<String> ok = response(200);
        when(webhookConfigRepository.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(failure, ok);

        notifier.onAuditEvent(event());

        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
