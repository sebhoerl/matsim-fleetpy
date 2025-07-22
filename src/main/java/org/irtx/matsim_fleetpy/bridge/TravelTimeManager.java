package org.irtx.matsim_fleetpy.bridge;

import org.irtx.matsim_fleetpy.bridge.communication.messages.TravelTimeQuery;
import org.irtx.matsim_fleetpy.bridge.communication.messages.TravelTimeResponse;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.util.TravelTime;

public class TravelTimeManager {
    private final Network network;
    private final TravelTime travelTime;

    public TravelTimeManager(Network network, TravelTime travelTime) {
        this.network = network;
        this.travelTime = travelTime;
    }

    public TravelTimeResponse query(TravelTimeQuery query, double now) {
        IdSet<Link> links = new IdSet<>(Link.class);

        for (String rawLink : query.links) {
            links.add(Id.createLinkId(rawLink));
        }

        if (links.size() == 0) {
            links.addAll(network.getLinks().keySet());
        }

        TravelTimeResponse response = new TravelTimeResponse();

        for (Id<Link> linkId : links) {
            Link link = network.getLinks().get(linkId);
            double value = travelTime.getLinkTravelTime(link, now, null, null);
            response.travelTimes.put(linkId.toString(), value);
        }

        return response;
    }
}
