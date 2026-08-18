package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.MonitorSnapshot;
import com.pkmprojects.mongodbserver.service.MonitorService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Live monitoring page (any authenticated user). The page subscribes to a
 * Server-Sent Events stream that pushes a {@link MonitorSnapshot} every two
 * seconds. A small daemon scheduler feeds the stream; a client disconnect
 * stops its ticks. Threads are daemon so they never keep the JVM alive.
 */
@Controller
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private static final long TICK_MILLIS = 2000;

    private final MonitorService monitorService;
    private final ScheduledExecutorService scheduler;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
        this.scheduler = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "monitor-sse");
            thread.setDaemon(true);
            return thread;
        });
    }

    @GetMapping("/monitor")
    public String monitor() {
        return "monitor";
    }

    @GetMapping("/monitor/stream")
    public ResponseEntity<SseEmitter> stream() {
        SseEmitter emitter = new SseEmitter(0L);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> sendTick(emitter),
                0, TICK_MILLIS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private void sendTick(SseEmitter emitter) {
        try {
            MonitorSnapshot snapshot = monitorService.getSnapshot();
            emitter.send(SseEmitter.event().name("tick").data(monitorService.serialize(snapshot)));
        } catch (IOException e) {
            // Client went away: complete the emitter and stop this stream.
            // Re-throwing ends the scheduled task so it does not keep ticking
            // into the void.
            log.debug("Monitor SSE client disconnected", e);
            emitter.complete();
            throw new SseStreamClosed(e);
        } catch (RuntimeException e) {
            // A snapshot failed. Close the stream so the browser reconnects
            // cleanly instead of sitting on a silent, stalled stream.
            log.warn("Monitor snapshot tick failed", e);
            emitter.completeWithError(e);
            throw e;
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    private static class SseStreamClosed extends RuntimeException {
        SseStreamClosed(IOException cause) {
            super(cause);
        }
    }
}