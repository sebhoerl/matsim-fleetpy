import zmq, json, gzip, xml.sax

import numpy as np
import numpy.linalg as la
import scipy.optimize as sopt

"""
Euclidean bipartite matching dispatcher for MATSim
"""

# Some settings
interval = 30
algorithm = "best-response" # bipartite-matching

# Load the network to obtain link coordinates
class NetworkHandler(xml.sax.ContentHandler):
    def __init__(self):
        self.node_coordinates = {}
        self.link_coordinates = {}

    def startElement(self, name, attributes):
        if name == "node":
            self.node_coordinates[attributes["id"]] = np.array((float(attributes["x"]), float(attributes["y"])))
        elif name == "link":
            self.link_coordinates[attributes["id"]] = 0.5 * (self.node_coordinates[attributes["from"]] + self.node_coordinates[attributes["to"]])

network_handler = NetworkHandler()
with gzip.open("../scenario/network.xml.gz") as f:
    xml.sax.parse(f, network_handler)

# start ZMQ
context = zmq.Context()

# open connection to MATSim
socket = context.socket(zmq.REQ)
socket.connect("tcp://localhost:9001")

# initialize conversation
socket.send(json.dumps({
    "@message": "initialization"
}).encode())

# track vehicles
vehicles = {}
requests = {}

# some tracking
statistics = { "time": 0.0, "pending": 0, "onboard": 0, "done": 0 }

# loop over the time steps
while True:
    # MATSim sends us the state
    state = json.loads(socket.recv())
    time = 0.0

    # Prepare the answer to MATSim
    assignment = { "@message": "assignment", "stops": dict() }

    # A new iteration has started
    if state["@message"] == "iteration":
        requests, vehicles = {}, {}
        statistics = { k: 0 for k in statistics.keys() }

        for vehicle in state["vehicles"]:
            # register vehicle
            vehicles[vehicle["id"]] = vehicle
            
            # initially not occupied
            vehicle["occupied"] = False
            vehicle["request"] = None
            vehicle["state"] = "stay"

            # convert initial link to coordinates
            vehicle["location"] = network_handler.link_coordinates[vehicle["startLink"]]

    # A new time step
    elif state["@message"] == "state":
        for request in state["submitted"]:
            # register request
            requests[request["id"]] = request

            # initially not onboard
            request["onboard"] = False
            request["vehicle"] = None

            # obtain coordinates
            request["origin"] = network_handler.link_coordinates[request["originLink"]]
            request["destination"] = network_handler.link_coordinates[request["destinationLink"]]

            # statistics
            statistics["pending"] += 1

        for r, v in state["pickedUp"].items():
            # vehicle did pickup
            vehicles[v]["occupied"] = True
            requests[r]["onboard"] = True
            
            # statistics
            statistics["pending"] -= 1
            statistics["onboard"] += 1

        for r, v in state["droppedOff"].items():
            # vehicle did dropoff, so can be assigned again
            vehicles[v]["request"] = None
            vehicles[v]["occupied"] = False

            # remove request
            del requests[r]

            # statistics
            statistics["onboard"] -= 1
            statistics["done"] += 1

        for vehicle in state["vehicles"]:
            # update vehicle location
            location = network_handler.link_coordinates[vehicle["currentLink"]]
            vehicles[vehicle["id"]]["location"] = location

            # and state
            vehicles[vehicle["id"]]["state"] = vehicle["state"]

        time = state["time"]
        statistics["time"] = state["time"]

    # end of the simulation
    if state["@message"] == "finalization":
        break

    if time % interval == 0:
        # generate list of assignable vehicles
        assignable_vehicles = []
        for vehicle in vehicles.values():
            if vehicle["occupied"]: 
                continue
            
            if vehicle["state"] == "stop" and vehicle["request"] is not None: 
                continue
            
            assignable_vehicles.append(vehicle)

        # generate list of assignable requests
        assignable_requests = []
        for request in requests.values():
            if request["onboard"]: 
                continue

            if request["vehicle"] is not None:
                if request["vehicle"]["state"] == "stop": 
                    continue

            assignable_requests.append(request)

        if len(assignable_vehicles) > 0 and len(assignable_requests) > 0:
            # obtain locations
            vehicle_locations = np.array([v["location"] for v in assignable_vehicles])
            request_locations = np.array([r["origin"] for r in assignable_requests])

            # calculate distances
            distances = np.array([
                la.norm(vehicle_locations - request_location, axis = 1)
                for request_location in request_locations
            ])

            # by default, clear schedules
            for vehicle in assignable_vehicles:
                assignment["stops"][vehicle["id"]] = []

            if algorithm == "best-response":
                # perform best-response assignment
                matches = []

                while np.any(np.isfinite(distances)):
                    # find index with shortest distance from vehicle to pickup
                    r, v = np.unravel_index(np.argmin(distances), distances.shape)

                    # avoid matching again
                    distances[r,:] = np.inf
                    distances[:,v] = np.inf

                    matches.append((r, v))

            else:
                # perform bipartite matching
                matches = [pair for pair in zip(*sopt.linear_sum_assignment(distances))]

            # implement matches
            for r, v in matches:
                # obtain match
                selected_request = assignable_requests[r]
                selected_vehicle = assignable_vehicles[v]

                # discard matching
                if selected_request["vehicle"] is not None:
                    selected_request["vehicle"]["request"] = None

                if selected_vehicle["request"] is not None:
                    selected_vehicle["request"]["vehicle"] = None

                # implement matching
                selected_vehicle["request"] = selected_request
                selected_request["vehicle"] = selected_vehicle

                # insert a pickup stop at the origin and a dropoff stop at the destination
                assignment["stops"][selected_vehicle["id"]] = [{
                    "link": selected_request["originLink"],
                    "pickup": [selected_request["id"]],
                    "stopDuration": 30.0
                }, {
                    "link": selected_request["destinationLink"],
                    "dropoff": [selected_request["id"]],
                    "stopDuration": 30.0
                }]

        # statistics
        print(statistics)

    # send the assignment to MATSim
    socket.send(json.dumps(assignment).encode())
