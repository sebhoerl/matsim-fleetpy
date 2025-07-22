package org.irtx.matsim_fleetpy.bridge.communication.messages;

import java.util.LinkedList;
import java.util.List;

public class TravelTimeQuery extends AbstractMessage {
    public List<String> links = new LinkedList<>();
}
