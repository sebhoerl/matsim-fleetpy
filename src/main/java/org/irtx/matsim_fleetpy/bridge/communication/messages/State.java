package org.irtx.matsim_fleetpy.bridge.communication.messages;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class State extends AbstractMessage {
    public double time;

    public Map<String, String> pickedUp = new HashMap<>();
    public Map<String, String> droppedOff = new HashMap<>();

    static public class Vehicle {
        public String id;

        public String currentLink;
        public double currentExitTime = Double.NEGATIVE_INFINITY;

        public String divergeLink = null;
        public double divergeTime = Double.NEGATIVE_INFINITY;

        public String state; // stay, stop, drive
    }

    public List<Vehicle> vehicles = new LinkedList<>();

    static public class Request {
        public String id;
        public String originLink;
        public String destinationLink;

        public double earliestPickupTime;
        public double latestPickupTime;
        public double latestArrivalTime;

        final public int size = 1;
    }

    public List<Request> submitted = new LinkedList<>();
}
