package org.irtx.matsim_fleetpy.bridge;

import org.irtx.matsim_fleetpy.bridge.communication.CommunicationManager;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;

import com.google.inject.Singleton;

public class FleetPyQSimModule extends AbstractDvrpModeQSimModule {
    public FleetPyQSimModule(String mode) {
        super(mode);
    }

    @Override
    protected void configureQSim() {
        bindModal(FleetPyOptimizer.class).toProvider(modalProvider(getter -> {
            Network network = getter.getModal(Network.class);
            TravelTime travelTime = getter.getModal(TravelTime.class);

            LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network,
                    new OnlyTimeDependentTravelDisutility(travelTime), travelTime);

            return new FleetPyOptimizer(
                    getter.getModal(CommunicationManager.class), //
                    getter.getModal(ScheduleTimingUpdater.class), //
                    getter.getModal(Fleet.class), //
                    network, //
                    getter.getModal(DrtTaskFactory.class), //
                    travelTime, //
                    router, //
                    getter.get(EventsManager.class), //
                    getMode());
        })).in(Singleton.class);

        addMobsimScopeEventHandlerBinding().to(modalKey(FleetPyOptimizer.class));
        addModalComponent(DrtOptimizer.class, modalKey(FleetPyOptimizer.class));
    }
}
