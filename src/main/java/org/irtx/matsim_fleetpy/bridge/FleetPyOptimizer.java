package org.irtx.matsim_fleetpy.bridge;

import java.util.LinkedList;
import java.util.List;

import org.irtx.matsim_fleetpy.bridge.communication.CommunicationManager;
import org.irtx.matsim_fleetpy.bridge.communication.messages.Assignment;
import org.irtx.matsim_fleetpy.bridge.communication.messages.Iteration;
import org.irtx.matsim_fleetpy.bridge.communication.messages.State;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.schedule.DrtDriveTask;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.schedule.DrtTaskBaseType;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEvent;
import org.matsim.contrib.dvrp.path.VrpPath;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelDataImpl;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.schedule.DriveTask;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedule.ScheduleStatus;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.contrib.dvrp.tracker.OnlineDriveTaskTracker;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;

import com.google.common.base.Preconditions;

public class FleetPyOptimizer implements DrtOptimizer, PassengerPickedUpEventHandler, PassengerDroppedOffEventHandler,
        MobsimScopeEventHandler {
    private final CommunicationManager communicationManager;
    private final ScheduleTimingUpdater scheduleTimingUpdater;
    private final Fleet fleet;
    private final Network network;
    private final DrtTaskFactory taskFactory;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;
    private final EventsManager eventsManager;
    private final String mode;

    private boolean initialized = false;
    private boolean isFirstStep = true;

    public FleetPyOptimizer(CommunicationManager communicationManager, ScheduleTimingUpdater scheduleTimingUpdater,
            Fleet fleet, Network network, DrtTaskFactory taskFactory, TravelTime travelTime,
            LeastCostPathCalculator router, EventsManager eventsManager, String mode) {
        this.communicationManager = communicationManager;
        this.scheduleTimingUpdater = scheduleTimingUpdater;
        this.fleet = fleet;
        this.network = network;
        this.taskFactory = taskFactory;
        this.travelTime = travelTime;
        this.router = router;
        this.eventsManager = eventsManager;
        this.mode = mode;
    }

    @Override
    public void notifyMobsimBeforeSimStep(@SuppressWarnings("rawtypes") MobsimBeforeSimStepEvent e) {
        if (!isFirstStep) {
            Assignment assignment = update(e.getSimulationTime());
            implement(assignment, e.getSimulationTime());
        } else {
            isFirstStep = false;
        }
    }

    private List<Request> submitted = new LinkedList<>();
    private IdMap<Request, AcceptedDrtRequest> requests = new IdMap<>(Request.class);

    @Override
    public void requestSubmitted(Request request) {
        synchronized (submitted) {
            submitted.add(request);
        }
    }

    private IdMap<Request, Id<DvrpVehicle>> pickedUp = new IdMap<>(Request.class);
    private IdMap<Request, Id<DvrpVehicle>> droppedOff = new IdMap<>(Request.class);

    public Assignment update(double time) {
        if (!initialized) {
            List<Iteration.Vehicle> initialVehicles = new LinkedList<>();

            for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
                Iteration.Vehicle initialVehicle = new Iteration.Vehicle();
                initialVehicles.add(initialVehicle);

                initialVehicle.id = vehicle.getId().toString();
                initialVehicle.capacity = (int) vehicle.getCapacity().getElement(0);
                initialVehicle.startLink = vehicle.getStartLink().getId().toString();
            }

            initialized = true;
            return communicationManager.startIteration(initialVehicles);
        } else {
            State state = new State();
            state.time = time;

            for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
                State.Vehicle vehicleState = new State.Vehicle();
                vehicleState.id = vehicle.getId().toString();
                state.vehicles.add(vehicleState);

                if (vehicle.getSchedule().getStatus().equals(ScheduleStatus.STARTED)) {
                    Task currentTask = vehicle.getSchedule().getCurrentTask();
                    if (DrtTaskBaseType.STAY.isBaseTypeOf(currentTask)) {
                        vehicleState.state = "stay";

                        StayTask stayTask = (StayTask) currentTask;
                        vehicleState.currentLink = stayTask.getLink().getId().toString();
                        vehicleState.currentExitTime = stayTask.getEndTime();

                        vehicleState.divergeLink = vehicleState.currentLink;
                        vehicleState.divergeTime = time;
                    } else if (DrtTaskBaseType.STOP.isBaseTypeOf(currentTask)) {
                        vehicleState.state = "stop";

                        DrtStopTask stopTask = (DrtStopTask) currentTask;
                        vehicleState.currentLink = stopTask.getLink().getId().toString();
                        vehicleState.currentExitTime = stopTask.getEndTime();

                        vehicleState.divergeLink = vehicleState.currentLink;
                        vehicleState.divergeTime = vehicleState.currentExitTime;
                    } else if (DrtTaskBaseType.DRIVE.isBaseTypeOf(currentTask)) {
                        vehicleState.state = "drive";

                        DriveTask driveTask = (DriveTask) currentTask;
                        OnlineDriveTaskTracker tracker = (OnlineDriveTaskTracker) driveTask.getTaskTracker();

                        VrpPath path = tracker.getPath();
                        vehicleState.currentLink = path.getLink(tracker.getCurrentLinkIdx()).getId().toString();

                        vehicleState.currentExitTime = tracker.getCurrentLinkEnterTime();
                        vehicleState.currentExitTime += path.getLinkTravelTime(tracker.getCurrentLinkIdx());

                        vehicleState.divergeLink = tracker.getDiversionPoint().link.getId().toString();
                        vehicleState.divergeTime = tracker.getDiversionPoint().time;
                    } else {
                        throw new IllegalStateException();
                    }
                } else {
                    StayTask stayTask = (StayTask) Schedules.getLastTask(vehicle.getSchedule());
                    vehicleState.state = "stay";
                    vehicleState.currentLink = stayTask.getLink().getId().toString();
                    vehicleState.currentExitTime = Double.POSITIVE_INFINITY;
                }
            }

            for (Request request : submitted) {
                DrtRequest drtRequest = (DrtRequest) request;

                State.Request requestState = new State.Request();
                state.submitted.add(requestState);
                requestState.id = drtRequest.getId().toString();

                requestState.originLink = drtRequest.getFromLink().getId().toString();
                requestState.destinationLink = drtRequest.getToLink().getId().toString();

                requestState.earliestPickupTime = drtRequest.getEarliestStartTime();
                requestState.latestPickupTime = drtRequest.getLatestStartTime();
                requestState.latestArrivalTime = drtRequest.getLatestArrivalTime();

                AcceptedDrtRequest acceptedRequest = AcceptedDrtRequest.createFromOriginalRequest(drtRequest);
                requests.put(request.getId(), acceptedRequest);
                requestEntries.put(request.getId(), new RequestEntry());
            }

            submitted.clear();

            for (var entry : pickedUp.entrySet()) {
                state.pickedUp.put(entry.getKey().toString(), entry.getValue().toString());
            }

            for (var entry : droppedOff.entrySet()) {
                state.droppedOff.put(entry.getKey().toString(), entry.getValue().toString());
            }

            pickedUp.clear();
            droppedOff.clear();

            return communicationManager.update(state);
        }
    }

    @Override
    public void nextTask(DvrpVehicle vehicle) {
        scheduleTimingUpdater.updateBeforeNextTask(vehicle);
        vehicle.getSchedule().nextTask();
    }

    @Override
    public void handleEvent(PassengerPickedUpEvent event) {
        synchronized (pickedUp) {
            pickedUp.put(event.getRequestId(), event.getVehicleId());
        }
    }

    @Override
    public void handleEvent(PassengerDroppedOffEvent event) {
        synchronized (pickedUp) {
            droppedOff.put(event.getRequestId(), event.getVehicleId());
        }

        synchronized (requests) {
            requests.remove(event.getRequestId());
            requestEntries.remove(event.getRequestId());
        }
    }

    private class RequestEntry {
        Id<DvrpVehicle> pickupVehicleId;
        Id<DvrpVehicle> dropoffVehicleId;
        boolean scheduled = false;
    }

    private IdMap<Request, RequestEntry> requestEntries = new IdMap<>(Request.class);

    private void implement(Assignment assignment, double now) {
        // first, clear the schedules of vehicles that get things rearranged
        for (String vehicleId : assignment.stops.keySet()) {
            DvrpVehicle vehicle = fleet.getVehicles().get(Id.create(vehicleId, DvrpVehicle.class));
            Schedule schedule = vehicle.getSchedule();
            Task currentTask = schedule.getCurrentTask();

            // book-keeping
            for (int i = currentTask.getTaskIdx(); i < schedule.getTaskCount(); i++) {
                Task task = schedule.getTasks().get(i);

                if (task instanceof DrtStopTask stopTask) {
                    for (Id<Request> requestId : stopTask.getPickupRequests().keySet()) {
                        requestEntries.get(requestId).pickupVehicleId = null;
                    }

                    for (Id<Request> requestId : stopTask.getPickupRequests().keySet()) {
                        requestEntries.get(requestId).dropoffVehicleId = null;
                    }
                }
            }

            // clearing
            while (currentTask != Schedules.getLastTask(schedule)) {
                schedule.removeLastTask();
            }

            if (currentTask instanceof DrtStayTask) {
                currentTask.setEndTime(now);
            }
        }

        // next, reconstruct the schedules
        for (var vehicleEntry : assignment.stops.entrySet()) {
            DvrpVehicle vehicle = fleet.getVehicles().get(Id.create(vehicleEntry.getKey(), DvrpVehicle.class));
            Schedule schedule = vehicle.getSchedule();
            Task currentTask = schedule.getCurrentTask();

            if (vehicleEntry.getValue().size() > 0) {
                for (int i = 0; i < vehicleEntry.getValue().size(); i++) {
                    var stop = vehicleEntry.getValue().get(i);
                    Link stopLink = network.getLinks().get(Id.createLinkId(stop.link));

                    // move to the next location
                    if (i == 0 && currentTask instanceof DriveTask driveTask) {
                        // we need to divert the current drive
                        OnlineDriveTaskTracker tracker = (OnlineDriveTaskTracker) driveTask.getTaskTracker();

                        final VrpPathWithTravelData path;
                        if (stop.route == null) {
                            path = VrpPaths.calcAndCreatePathForDiversion(tracker.getDiversionPoint(), stopLink, router,
                                    travelTime);
                        } else {
                            path = createPath(stop.route, tracker.getDiversionPoint().time);
                        }

                        tracker.divertPath(path);
                    } else if (currentTask instanceof StayTask stayTask && stayTask.getLink() != stopLink) {
                        // we need to add a new drive
                        final VrpPathWithTravelData path;
                        if (stop.route == null) {
                            path = VrpPaths.calcAndCreatePath(stayTask.getLink(), stopLink, currentTask.getEndTime(),
                                    router, travelTime);
                        } else {
                            path = createPath(stop.route, stayTask.getEndTime());
                        }

                        DriveTask driveTask = taskFactory.createDriveTask(vehicle, path, DrtDriveTask.TYPE);
                        schedule.addTask(driveTask);

                        currentTask = driveTask;
                    }

                    // insert a potential wait before the next stop
                    if (currentTask.getEndTime() < stop.earliestStartTime) {
                        double beginTime = currentTask.getEndTime();
                        double endTime = stop.earliestStartTime;

                        DrtStayTask stayTask = taskFactory.createStayTask(vehicle, beginTime, endTime, stopLink);
                        schedule.addTask(stayTask);
                        currentTask = stayTask;
                    }

                    // insert the next stop
                    if (stop.pickup.size() > 0 || stop.dropoff.size() > 0) {
                        double beginTime = currentTask.getEndTime();
                        double endTime = beginTime + stop.stopDuration;

                        DrtStopTask stopTask = taskFactory.createStopTask(vehicle, beginTime, endTime, stopLink);

                        for (String pickupId : stop.pickup) {
                            AcceptedDrtRequest request = requests.get(Id.create(pickupId, Request.class));
                            stopTask.addPickupRequest(request);

                            Preconditions.checkState(requestEntries.get(request.getId()).pickupVehicleId == null,
                                    "Request " + request.getId() + " is assigned for pickup to vehicle "
                                            + vehicle.getId()
                                            + " but is already assigned to vehicle "
                                            + requestEntries.get(request.getId()).pickupVehicleId);
                            requestEntries.get(request.getId()).pickupVehicleId = vehicle.getId();
                        }

                        for (String dropoffId : stop.dropoff) {
                            AcceptedDrtRequest request = requests.get(Id.create(dropoffId, Request.class));
                            stopTask.addDropoffRequest(request);

                            Preconditions.checkState(requestEntries.get(request.getId()).dropoffVehicleId == null,
                                    "Request " + request.getId() + " is assigned for dropoff to vehicle "
                                            + vehicle.getId()
                                            + " but is already assigned to vehicle "
                                            + requestEntries.get(request.getId()).dropoffVehicleId);
                            requestEntries.get(request.getId()).dropoffVehicleId = vehicle.getId();
                        }

                        schedule.addTask(stopTask);
                        currentTask = stopTask;
                    }
                }
            } else {
                // stop driving
                if (currentTask instanceof DriveTask driveTask) {
                    OnlineDriveTaskTracker tracker = (OnlineDriveTaskTracker) driveTask.getTaskTracker();

                    final VrpPathWithTravelData path = VrpPaths.calcAndCreatePathForDiversion(
                            tracker.getDiversionPoint(), tracker.getDiversionPoint().link, router,
                            travelTime);
                    tracker.divertPath(path);
                }
            }

            // make sure the schedule ends with an idle task
            if (currentTask.getEndTime() < vehicle.getServiceEndTime()) {
                if (currentTask instanceof DrtStayTask stayTask) {
                    stayTask.setEndTime(vehicle.getServiceEndTime());
                } else {
                    final Link currentLink;
                    if (currentTask instanceof StayTask stayTask) {
                        currentLink = stayTask.getLink();
                    } else {
                        currentLink = ((DriveTask) currentTask).getPath().getToLink();
                    }

                    StayTask stayTask = taskFactory.createStayTask(vehicle, currentTask.getEndTime(),
                            vehicle.getServiceEndTime(), currentLink);
                    schedule.addTask(stayTask);
                }
            }
        }

        // validation
        for (var item : requestEntries.entrySet()) {
            var entry = item.getValue();

            if (entry.pickupVehicleId != null && entry.dropoffVehicleId == null) {
                throw new IllegalStateException("Request " + item.getKey() + " is assigned for pickup to vehicle "
                        + entry.pickupVehicleId + " but has not dropoff assigned");
            }

            if (entry.pickupVehicleId == null && entry.dropoffVehicleId != null) {
                throw new IllegalStateException("Request " + item.getKey() + " is assigned for dropoff to vehicle "
                        + entry.dropoffVehicleId + " but has not pickup assigned");
            }

            if (entry.pickupVehicleId != entry.dropoffVehicleId) {
                throw new IllegalStateException("Request " + item.getKey() + " is assigned for pickup to vehicle "
                        + entry.pickupVehicleId + " and for dropoff to vehicle " + entry.dropoffVehicleId);
            }

            if (entry.pickupVehicleId != null && !entry.scheduled) {
                entry.scheduled = true;

                AcceptedDrtRequest request = requests.get(item.getKey());

                double pickupTime = 0.0;
                double dropoffTime = 0.0;

                DvrpVehicle vehicle = fleet.getVehicles().get(entry.pickupVehicleId);
                Schedule schedule = vehicle.getSchedule();

                for (Task task : schedule.getTasks().subList(schedule.getCurrentTask().getTaskIdx(),
                        schedule.getTaskCount())) {
                    if (task instanceof DrtStopTask stopTask) {
                        if (stopTask.getPickupRequests().containsKey(request.getId())) {
                            pickupTime = stopTask.getEndTime();
                        }

                        if (stopTask.getDropoffRequests().containsKey(request.getId())) {
                            dropoffTime = stopTask.getBeginTime();
                        }
                    }
                }

                eventsManager.processEvent(new PassengerRequestScheduledEvent(now, mode, request.getId(),
                        request.getPassengerIds(), entry.pickupVehicleId, pickupTime, dropoffTime));
            }
        }
    }

    private VrpPathWithTravelData createPath(List<String> rawRoute, double departureTime) {
        double routeTravelTime = 0.0;
        double enterTime = departureTime;

        Link[] links = new Link[rawRoute.size()];
        double[] travelTimes = new double[rawRoute.size()];

        for (int k = 0; k < rawRoute.size(); k++) {
            Link link = network.getLinks().get(Id.createLinkId(rawRoute.get(k)));
            links[k] = link;
            travelTimes[k] = travelTime.getLinkTravelTime(link, enterTime, null, null);
            routeTravelTime += travelTimes[k];
            enterTime += travelTimes[k];
        }

        return new VrpPathWithTravelDataImpl(departureTime, routeTravelTime, links,
                travelTimes);
    }
}
