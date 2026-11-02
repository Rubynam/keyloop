package com.keyloop.scheduler.api.config.tracing;

import com.keyloop.scheduler.api.constant.TracingConstants;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Stops an observation for an actuator request before it starts, so health checks make
 * no trace. Docker and load balancers call health about every 10 s: about 8 600 traces
 * a day per container that tell us nothing. The JDBC and Redis calls of the check then
 * have no parent, and {@link RootSpanExportingPredicate} drops them. Spine AD-3.
 *
 * <pre>
 * observation             request path              kept
 * http.server.requests    /api/v1/availability      yes
 * http.server.requests    /actuator/health          no
 * http.server.requests    /actuator/health/ready    no
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
        return !isActuatorRequest(name, context);
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
