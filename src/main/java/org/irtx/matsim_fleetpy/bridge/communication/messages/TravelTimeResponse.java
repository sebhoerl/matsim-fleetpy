package org.irtx.matsim_fleetpy.bridge.communication.messages;

import java.util.HashMap;
import java.util.Map;

public class TravelTimeResponse extends AbstractMessage {
    public Map<String, Double> travelTimes = new HashMap<>();
}
