package com.keyloop.scheduler.logging;

/**
 * Marks an exception that is the caller's mistake (bad input, unknown id) and is answered
 * with a 4xx. {@link MethodLoggingAspect} logs it at WARN, not ERROR, so ERROR stays for
 * failures someone must fix and alerts on ERROR do not fire on every bad request.
 *
 * <pre>
 * NotFoundException implements ClientError  →  WARN  [Svc][find][id][...][NotFoundException: ...]
 * DataAccessException                       →  ERROR [Svc][find][id][...][DataAccessException: ...]
 * </pre>
 */
public interface ClientError {
}
