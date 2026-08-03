package io.github.fourilla.endervault.outbound;

import io.github.fourilla.endervault.config.NasProperties;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class OutboundRouteStateService {

    private final NetworkRoute initialRoute;
    private final AtomicReference<NetworkRoute> currentRoute;

    public OutboundRouteStateService(NasProperties nasProperties) {
        initialRoute = Objects.requireNonNull(nasProperties.getOutbound().getInitialRoute(), "initialRoute");
        currentRoute = new AtomicReference<>(initialRoute);
    }

    public NetworkRoute initialRoute() {
        return initialRoute;
    }

    public NetworkRoute currentRoute() {
        return currentRoute.get();
    }

    public NetworkRoute changeRoute(NetworkRoute route) {
        return currentRoute.getAndSet(Objects.requireNonNull(route, "route"));
    }
}
