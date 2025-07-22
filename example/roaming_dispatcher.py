import zmq, json, gzip, xml.sax
import numpy as np
import networkx as nx

"""
Random roaming dispatcher for MATSim
"""

seed = 0
roaming_interval = 300
statistics_interval = 60
travel_time_interval = 600

# Initialize RNG
random_state = np.random.RandomState(seed)

# Load the network to obtain link coordinates
class NetworkHandler(xml.sax.ContentHandler):
    def __init__(self):
        self.graph = nx.DiGraph()
        self.from_node = {}
        self.to_node = {}
        self.links = {}

    def startElement(self, name, attributes):
        if name == "link":
            travel_time = float(attributes["length"]) / float(attributes["freespeed"])
            self.graph.add_edge(attributes["from"], attributes["to"], travel_time = travel_time)
            
            self.from_node[attributes["id"]] = attributes["from"]
            self.to_node[attributes["id"]] = attributes["to"]
            self.links[(attributes["from"], attributes["to"])] = attributes["id"]
        
network = NetworkHandler()
with gzip.open("../scenario/network.xml.gz") as f:
    xml.sax.parse(f, network)

# start ZMQ
context = zmq.Context()

# open connection to MATSim
socket = context.socket(zmq.REQ)
socket.connect("tcp://localhost:9001")

# initialize conversation
socket.send(json.dumps({
    "@message": "initialization"
}).encode())

# track vehicle and requests
vehicles = {}
requests = {}

# loop over the time steps
while True:
    # MATSim sends us the state
    state = json.loads(socket.recv())

    # Prepare the answer to MATSim
    assignment = { "@message": "assignment", "stops": dict() }
    time = 0

    # A new iteration has started
    if state["@message"] == "iteration":
        vehicles, requests = {}, {} # reset

        for vehicle in state["vehicles"]:
            # track all the available vehicles
            vehicles[vehicle["id"]] = vehicle

            vehicle["location"] = vehicle["startLink"]
            vehicle["diversion"] = vehicle["startLink"]
            vehicle["assigned"] = False
            vehicle["state"] = "inactive"

    # A new time step
    elif state["@message"] == "state":
        time = state["time"]

        for request in state["submitted"]:
            # register incoming request
            requests[request["id"]] = request
            request["onboard"] = False
            request["assigned"] = False

        for r in state["pickedUp"].keys():
            # vehicle picked up passenger
            requests[r]["onboard"] = True
        
        for r, v in state["droppedOff"].items():
            # vehicle did dropoff, so can be assigned again
            vehicles[v]["assigned"] = False

            # delete request
            del requests[r]

        for vehicle in state["vehicles"]:
            vehicles[vehicle["id"]]["location"] = vehicle["currentLink"]
            vehicles[vehicle["id"]]["diversion"] = vehicle["divergeLink"]
            vehicles[vehicle["id"]]["state"] = vehicle["state"]

    # end of the simulation
    if state["@message"] == "finalization":
        break

    # loop through all assignable vehicles and check if one is by chance on the same link as request
    for vehicle in vehicles.values():
        for request in requests.values():
            # check that both are still assignable
            if not request["assigned"] and not vehicle["assigned"] and vehicle["state"] != "inactive":
                if request["originLink"] == vehicle["location"]:
                    # we found a match

                    # track state
                    request["assigned"] = True
                    vehicle["assigned"] = True

                    # routing for pickup
                    #pickup_route = nx.shortest_path(graph, vehicle["diversion"], request["originLink"])
                    #dropoff_route = nx.shortest_path(graph, request["originLink"], request["destinationLink"])

                    # insert a pickup stop at the origin and a dropoff stop at the destination
                    assignment["stops"][vehicle["id"]] = [{
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

    if time % roaming_interval == 0:
        # loop through all vehicles that remain assignable and divert them
        for vehicle in vehicles.values():
            if not vehicle["assigned"] and vehicle["state"] != "inactive":
                candidates = list(network.links.values())
                selection = candidates[random_state.randint(len(candidates))]

                route = nx.shortest_path(
                    network.graph, 
                    network.to_node[vehicle["diversion"]], 
                    network.from_node[selection], weight = "travel_time")
                
                route = [vehicle["diversion"]] + [
                    network.links[(u,v)]
                    for u, v in zip(route[:-1], route[1:])
                ] + [selection]

                # send vehicle to random link
                assignment["stops"][vehicle["id"]] = [{
                    "link": selection,
                    "route": route
                }]

    if time % statistics_interval == 0:
        # statistics
        statistics = { "time": time, "pending": 0, "onboard": 0, "roaming": 0, "assigned": 0 }
        
        for request in requests.values():
            statistics["onboard" if request["onboard"] else "pending"] += 1

        for vehicle in vehicles.values():
            statistics["assigned" if vehicle["assigned"] else "roaming"] += 1

        print(statistics)

    if time % travel_time_interval == 0 and travel_time_interval > 0:
        print("updating network travel times ...")
        # request travel times and update network
        travel_time_query = { "@message": "travel_time_query" }
        socket.send(json.dumps(travel_time_query).encode())

        travel_time_response = state = json.loads(socket.recv())
        data = {}

        for link, value in travel_time_response["travelTimes"].items():
            u, v = network.from_node[link], network.to_node[link]
            data[(u, v)] = { "travel_time": value }

        nx.set_edge_attributes(network.graph, data)
        print("  done.")

    # send the assignment to MATSim
    socket.send(json.dumps(assignment).encode())
