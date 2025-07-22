package org.irtx.matsim_fleetpy.bridge.communication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.irtx.matsim_fleetpy.bridge.TravelTimeManager;
import org.irtx.matsim_fleetpy.bridge.communication.messages.AbstractMessage;
import org.irtx.matsim_fleetpy.bridge.communication.messages.Assignment;
import org.irtx.matsim_fleetpy.bridge.communication.messages.Finalization;
import org.irtx.matsim_fleetpy.bridge.communication.messages.Initialization;
import org.irtx.matsim_fleetpy.bridge.communication.messages.Iteration;
import org.irtx.matsim_fleetpy.bridge.communication.messages.State;
import org.irtx.matsim_fleetpy.bridge.communication.messages.TravelTimeQuery;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Verify;

public class CommunicationManager
        implements StartupListener, ShutdownListener, IterationStartsListener {
    private final Logger logger = LogManager.getLogger(CommunicationManager.class);

    private final int port;

    private ZContext context;
    private ZMQ.Socket socket;
    private final ObjectMapper mapper = new ObjectMapper();

    private int iteration;
    private boolean initialized = false;

    private final TravelTimeManager travelTimeManager;

    public CommunicationManager(int port, TravelTimeManager travelTimeManager) {
        this.port = port;
        this.travelTimeManager = travelTimeManager;
    }

    @Override
    public void notifyStartup(StartupEvent event) {
        logger.info("Creating ZMQ context ...");
        context = new ZContext();

        logger.info("Setting up ZMQ socket on port " + port + "...");
        socket = context.createSocket(SocketType.REP);
        socket.bind("tcp://*:" + port);
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        this.iteration = event.getIteration();
    }

    private void initialize() {
        try {
            logger.info("Waiting for initialization...");
            AbstractMessage response = mapper.readValue(socket.recv(), AbstractMessage.class);

            Verify.verify(response instanceof Initialization);
            logger.info("OK!");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Assignment startIteration(List<Iteration.Vehicle> vehicles) {
        try {
            if (!initialized) {
                initialize();
                initialized = true;
            }

            Iteration message = new Iteration();
            message.iteration = iteration;
            message.vehicles = vehicles;

            logger.info("Initializing iteration " + iteration + "...");
            socket.send(mapper.writeValueAsBytes(message));

            AbstractMessage response;
            while (true) {
                logger.debug("... waiting for response ...");
                response = mapper.readValue(socket.recv(), AbstractMessage.class);

                if (response instanceof TravelTimeQuery query) {
                    logger.debug("... handling travel time query ...");
                    socket.send(mapper.writeValueAsBytes(travelTimeManager.query(query, 0.0)));
                } else {
                    break;
                }
            }

            Verify.verify(response instanceof Assignment);

            logger.info("OK!");
            return (Assignment) response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Assignment update(State state) {
        try {
            logger.debug("Sending state at " + state.time + "...");
            socket.send(mapper.writeValueAsBytes(state));

            AbstractMessage response;
            while (true) {
                logger.debug("... waiting for response ...");
                response = mapper.readValue(socket.recv(), AbstractMessage.class);

                if (response instanceof TravelTimeQuery query) {
                    logger.debug("... handling travel time query ...");
                    socket.send(mapper.writeValueAsBytes(travelTimeManager.query(query, state.time)));
                } else {
                    break;
                }
            }

            Verify.verify(response instanceof Assignment);

            logger.debug("OK!");
            return (Assignment) response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        try {
            logger.info("Sending finalization...");
            socket.send(mapper.writeValueAsBytes(new Finalization()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (context != null) {
            context.close();
        }
    }
}
