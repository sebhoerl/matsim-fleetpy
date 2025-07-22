package org.irtx.matsim_fleetpy.bridge.communication.messages;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, //
                include = JsonTypeInfo.As.PROPERTY, //
                property = "@message")
@JsonSubTypes({ //
                @Type(value = Initialization.class, name = "initialization"), //
                @Type(value = Finalization.class, name = "finalization"), //
                @Type(value = Iteration.class, name = "iteration"), //
                @Type(value = Assignment.class, name = "assignment"), //
                @Type(value = State.class, name = "state"), //
                @Type(value = TravelTimeQuery.class, name = "travel_time_query"), //
                @Type(value = TravelTimeResponse.class, name = "travel_time_response"), //
})
public class AbstractMessage {

}
