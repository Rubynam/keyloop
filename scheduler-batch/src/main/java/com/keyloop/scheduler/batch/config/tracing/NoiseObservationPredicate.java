package com.keyloop.scheduler.batch.config.tracing;

import com.keyloop.scheduler.batch.constant.TracingConstants;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Stops two noisy observations before they start. The relay ticks every 500 ms, about
 * 170 000 runs a day per instance, and most find no due row. Health checks come about
 * every 10 s, about 8 600 a day. Their JDBC and Redis calls then have no parent, and
 * {@link RootSpanExportingPredicate} drops them. A relay run that does work gets its own
 * business span later (story-outbox-tracing). Spine AD-3.
 *
 * <pre>
 * observation                 target / path          kept
 * tasks.scheduled.execution   JobScheduler           yes
 * tasks.scheduled.execution   OutboxRelay            no
 * http.server.requests        /actuator/health       no
 * </pre>
 */
@Component
public class NoiseObservationPredicate implements ObservationPredicate {

    private final String actuatorBasePath;

    public NoiseObservationPredicate(WebEndpointProperties webEndpointProperties) {
        this.actuatorBasePath = webEndpointProperties.getBasePath();
    }

    @Override
    public boolean test(String name, Observation.Context context) {
        return !isRelayTick(name, context) && !isActuatorRequest(name, context);
    }

    // getUserClass: the scheduled bean may be a CGLIB proxy, not the relay class itself
    private static boolean isRelayTick(String name, Observation.Context context) {
        return TracingConstants.SCHEDULED_TASK_EXECUTION.equals(name)
                && context instanceof ScheduledTaskObservationContext task
                && TracingConstants.OUTBOX_RELAY_CLASS.equals(ClassUtils.getUserClass(task.getTargetClass()).getName());
    }

    // An empty base path (actuator at "/") would match every request: never drop then
    private boolean isActuatorRequest(String name, Observation.Context context) {
        if (!TracingConstants.HTTP_SERVER_REQUESTS.equals(name)
                || !(context instanceof ServerRequestObservationContext request)
                || actuatorBasePath.isEmpty()) {
            return false;
        }
        String uri = request.getCarrier().getRequestURI();
        String path = uri.substring(request.getCarrier().getContextPath().length());
        return path.equals(actuatorBasePath) || path.startsWith(actuatorBasePath + TracingConstants.PATH_SEPARATOR);
    }
}
