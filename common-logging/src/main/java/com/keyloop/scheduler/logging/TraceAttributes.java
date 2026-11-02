package com.keyloop.scheduler.logging;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Span and tag names that both apps write, so one name is never spelled two ways.
 * Empty for now: the outbox tracing story adds the {@code outbox.*} spans and their
 * tags here. Names used by one app stay in that feature's constants. Spine AD-7.
 *
 * <pre>
 * server-api  outbox.write ──trace_parent──► scheduler-batch  outbox.work
 *             event.id = 42                                   event.id = 42
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TraceAttributes {
}
