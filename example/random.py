import zmq, json

"""
Random assignment dispatcher for MATSim
"""

# start ZMQ
context = zmq.Context()

# open connection to MATSim
socket = context.socket(zmq.REQ)
socket.connect("tcp://localhost:9001")

# initialize conversation
socket.send(json.dumps({
    "@message": "initialization"
}).encode())

# track pending requests and unassigned vehicles
pending_requests = []
unassigned_vehicles = []
finished_requests = 0

# loop over the time steps
while True:
    # MATSim sends us the state
    state = json.loads(socket.recv())

    # Prepare the answer to MATSim
    assignment = { "@message": "assignment", "stops": dict() }

    # A new iteration has started
    if state["@message"] == "iteration":
        pending_requests, unassigned_vehicles = [], [] # reset

        for vehicle in state["vehicles"]:
            # track all the available vehicles
            unassigned_vehicles.append(vehicle["id"])

            #print("Added vehicle", vehicle["id"])

    # A new time step
    elif state["@message"] == "state":
        for request in state["submitted"]:
            # register incoming request
            pending_requests.append(request)

            #print("Added request", request["id"])
        
        for vehicle in state["droppedOff"].values():
            # vehicle did dropoff, so can be assigned again
            unassigned_vehicles.append(vehicle)
            finished_requests += 1

            # print("Vehicle", vehicle, "is availabile again")

    # end of the simulation
    if state["@message"] == "finalization":
        break

    # as long as we have pending requests and vehicles in the list ...
    while len(pending_requests) > 0 and len(unassigned_vehicles) > 0:
        # pop the top request and vehicle
        request = pending_requests.pop(0)
        vehicle = unassigned_vehicles.pop(0)
        
        # insert a pickup stop at the origin and a dropoff stop at the destination
        assignment["stops"][vehicle] = [{
            "link": request["originLink"],
            "pickup": [request["id"]],
            "stopDuration": 30.0,
            # deliberately not providing route -> will make MATSim route the path
        }, {
            "link": request["destinationLink"],
            "dropoff": [request["id"]],
            "stopDuration": 30.0,
            # deliberately not providing route -> will make MATSim route the path
        }]

        # print("Matched request", request["id"], "to", vehicle)

    # some stats
    print("PR:", len(pending_requests), "FR:", finished_requests, "UV:", len(unassigned_vehicles))

    # send the assignment to MATSim
    socket.send(json.dumps(assignment).encode())
