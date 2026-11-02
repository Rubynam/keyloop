package com.keyloop.scheduler.batch.config.tracing;

import com.keyloop.scheduler.batch.constant.TracingConstants;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.opentelemetry.api.trace.SpanId;
import org.springframework.stereotype.Component;

/**
 * Drops, at export time, a JDBC or Redis span that has no parent. Such a span comes from
 * an empty relay tick (about 170 000 a day per instance) or a health check, and would be
 * a one-span trace.
 * Lettuce sets its parent on the Netty event loop, after an {@code ObservationPredicate}
 * has run, so only here is "no parent" known for sure. Spine AD-3.
 *
 * <pre>
 * span                          parent        exported
 * query (jdbc.*)                heartbeat     yes
 * get   (db.system=redis)       heartbeat     yes
 * query (jdbc.*)                none          no
 * evalsha (db.system=redis)     none          no
 * tasks.scheduled.execution     none          yes
 * </pre>
 */
@Component
public class RootSpanExportingPredicate implements SpanExportingPredicate {

    @Override
    public boolean isExportable(FinishedSpan span) {
        return !(isRoot(span) && isDataAccess(span));
    }

    private static boolean isRoot(FinishedSpan span) {
        String parentId = span.getParentId();
        return parentId == null || !SpanId.isValid(parentId);
    }

    // Matched by tag: the span names are "query" and the Redis command, not the observation name
    private static boolean isDataAccess(FinishedSpan span) {
        return TracingConstants.DB_SYSTEM_REDIS.equals(span.getTags().get(TracingConstants.DB_SYSTEM_TAG))
                || span.getTags().keySet().stream().anyMatch(key -> key.startsWith(TracingConstants.JDBC_TAG_PREFIX));
    }
}
