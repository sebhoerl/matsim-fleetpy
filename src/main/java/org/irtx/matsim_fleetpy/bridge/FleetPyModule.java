package org.irtx.matsim_fleetpy.bridge;

import org.irtx.matsim_fleetpy.bridge.communication.CommunicationManager;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;

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
            return new CommunicationManager(port);
        })).in(Singleton.class);

        addControlerListenerBinding().to(modalKey(CommunicationManager.class));
    }
}
