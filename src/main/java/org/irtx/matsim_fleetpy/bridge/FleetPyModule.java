package org.irtx.matsim_fleetpy.bridge;

import org.irtx.matsim_fleetpy.bridge.communication.CommunicationManager;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.core.router.util.TravelTime;

import com.google.inject.Singleton;

public class FleetPyModule extends AbstractDvrpModeModule {
    private final int port;

    public FleetPyModule(String mode, int port) {
        super(mode);
        this.port = port;
    }

    @Override
    public void install() {
        bindModal(CommunicationManager.class).toProvider(modalProvider(getter -> {
            TravelTimeManager travelTimeManager = getter.getModal(TravelTimeManager.class);
            return new CommunicationManager(port, travelTimeManager);
        })).in(Singleton.class);

        bindModal(TravelTimeManager.class).toProvider(modalProvider(getter -> {
            Network network = getter.getModal(Network.class);
            TravelTime travelTime = getter.getModal(TravelTime.class);
            return new TravelTimeManager(network, travelTime);
        }));

        addControlerListenerBinding().to(modalKey(CommunicationManager.class));
    }
}
