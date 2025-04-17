package org.irtx.matsim_fleetpy.bridge.communication.messages;

import java.util.List;

public class Iteration extends AbstractMessage {
    public int iteration;
    public List<Vehicle> vehicles;

    static public class Vehicle {
        public String id;
        public String startLink;
        public int capacity;
    }
}
