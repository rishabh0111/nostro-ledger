package io.nostro.projection;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Keeps every span the service finishes, so a test can ask which trace they belonged to. Brave
 * picks up any {@link SpanHandler} bean; this is imported once, on the base class, so every test
 * shares the one context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CapturedSpans {

    /** A finished span, as much of it as the tests look at. */
    public record Finished(String traceId, String name, String kind) {
    }

    private static final List<Finished> FINISHED = new CopyOnWriteArrayList<>();

    @Bean
    SpanHandler capturingSpanHandler() {
        return new SpanHandler() {
            @Override
            public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                FINISHED.add(new Finished(span.traceId(), span.name(), String.valueOf(span.kind())));
                return true;
            }
        };
    }

    public static List<Finished> ofTrace(String traceId) {
        return FINISHED.stream().filter(span -> span.traceId().equals(traceId)).toList();
    }
}
