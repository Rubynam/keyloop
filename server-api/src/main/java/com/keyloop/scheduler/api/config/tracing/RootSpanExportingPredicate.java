package com.keyloop.scheduler.api.config.tracing;

import com.keyloop.scheduler.api.constant.TracingConstants;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.opentelemetry.api.trace.SpanId;
import org.springframework.stereotype.Component;

/**
 * Drops, at export time, a JDBC or Redis span that has no parent. Such a span comes from
 * a health check or another call outside any request, and would be a one-span trace.
 * Lettuce sets its parent on the Netty event loop, after an {@code ObservationPredicate}
 * has run, so only here is "no parent" known for sure. Spine AD-3.
 *
 * <pre>
 * span                          parent        exported
 * query (jdbc.*)                POST /…       yes
 * get   (db.system=redis)       POST /…       yes
 * query (jdbc.*)                none          no
 * evalsha (db.system=redis)     none          no
 * http GET /api/v1/…            none          yes
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
