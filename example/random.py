import zmq, json

context = zmq.Context()

socket = context.socket(zmq.REQ)
socket.connect("tcp://localhost:9001")

socket.send(json.dumps({
    "@message": "initialization"
}).encode())

pending_requests = []
unassigned_vehicles = []
finished_requests = 0

while True:
    state = json.loads(socket.recv())
    assignment = { "@message": "assignment", "stops": dict() }

    if state["@message"] == "iteration":
        pending_requests, unassigned_vehicles = [], []

        for vehicle in state["vehicles"]:
            # track all the available vehicles
            unassigned_vehicles.append(vehicle["id"])

            #print("Added vehicle", vehicle["id"])

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

    if state["@message"] == "finalization":
        exit()

    while len(pending_requests) > 0 and len(unassigned_vehicles) > 0:
        # pop the top request and vehicle
        request = pending_requests.pop(0)
        vehicle = unassigned_vehicles.pop(0)
        
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

    print("PR:", len(pending_requests), "FR:", finished_requests, "UV:", len(unassigned_vehicles))

    socket.send(json.dumps(assignment).encode())
