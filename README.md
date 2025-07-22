# MATSim / FleetPy bridge

## Example MATSim simulation

How to run the MATSim test simulation:

- You need to have Java installed an be able to run the run scripts (`Run*`) using your IDE. You IDE should manage the dependencies for Maven/Java. This should work out of the box in VSCode, IntelliJ or Eclipse if you import the present project as a Maven project.
- As input data, only a network for Paris in MATSim format is provided in `scenario/network.xml.gz`.
- To run a simulation, you first need to generate some dmeand in `scenario/demand.xml`. You can do so by running the `RunCreateDemand` script with the following command line arguments (for 1,000 requests with randomly distributed origins and destination around with a mean departure time of 10am and a standard deviation of 2 hours):

```bash
--network-path /path/to/scenario/network.xml.gz
--output-path /path/to/scenario/demand.xml
--requests 1000
```

- Second, you need to create a fleet in `scenario/fleet.xml`. You can do so by running the `RunCreateFleet` script with the following command line arguments (for 20 vehicles randomly distributed in the network):

```bash
--network-path /path/to/scenario/network.xml.gz
--output-path /path/to/scenario/fleet.xml
--vehicles 20 --seats 4
```

- Finally, you can run the MATSim simulation using `RunSimulation`:

```bash
--network-path /path/to/scenario/network.xml.gz
--fleet-path /path/to/scenario/fleet.xml
--demand-path /path/to/scenario/demand.xml
--output-path /path/to/scenario/output
```

- You can then find the outputs in `scenario/output`. Have a look, for instance, at `scenario/output/ITERS/it.0/0.occupancy_time_profiles_StackedArea_drt.png` which shows you the occupancy of the vehicles throghout the simulation.

## Example remote interface simulation

To perform dispatching using the remote interface, start the simulation has described above, but with an additional parameter:

```bash
--remote-port 9001
```

This will make MATSim listen on that port for the remote dispatcher. Communication is performed using the *ZeroMQ* library that is available in various languages. An example dispatcher is given in `example/random_dispatcher.py`. What it does is the following:

- It connects to MATSim on the port that is written in the script using *ZMQ*.
- It gives an initial ping to MATSim.
- It receives an initialization message for a new iteration, including the initial list of vehicles and their locations in the network.

In every subsequent step the script answsers with an *assignment* message (the first one is empty) and receives a *state* message.

- The script collects all submitted requests in a list that are coming in in every step.
- The script keeps a list of available vehicles (initially all of them).
- In every step, the script takes the first available vehilce of the list and the oldest request, and sets up a matching between the two. The vehicle is removed from the available list.
- As soon as the *state* indicates that a dropoff happened, the vehicle is added back tp the list of available vehicles.

In essence, this is a completely random unit capacity dispatcher that always matches randomly one vehicle with one request. As soon as the request is dropped off, another one can be assigned.

## More complex example

The script in `example/euclidean_dispatcher.py` provides a slightly more complex dispatcher:

- Every `N` (default 30) seconds, we search for all vehicles that are not currently carrying a passenger (or are stopping to pick up one up) and for all requests that are not currently onboard of (or currently entering) a vehicle. We call those the *assignable* vehicles.
- The script loads the network file to obtain Euclidean coordinates for each link. We obtain the origin coordinates of all assignable requests and the current position coordinates of all assignable vehicles.
- We set up a Euclidean distance matrix between all requests and vehicles.
- We then iteratively find the request-vehicle pair with the shortest distance by erasing the request / vehicle of the currently shortest pair from the distance matrix until no more assignable requests or vehicles are left.
- The obtain matchings are communicated to MATSim.
- In summary, we perform a best-response Euclidean distance matching.

**Bipartite matching**: Altenratively, you can set `algorithm = "bipartite-matching"` on top of the script. In that case, `scipy.optimize.linear_sum_assignment` will be used to solve a Global Bipartite Matching (GBM) problem that minimizes the sum of distances in each decision step (instead of the best resposne assignment).

## Roaming dispatcher using networkx

In `example/roaming_dispatcher.py` a dispatcher is presented that lets vehicles roam in the network. The example is especially useful as it shows how to perform routing on the dispatcher side instead of letting MATSim perform automatic routing (see below).

The dispatcher first loads the network as a `networkx` network and then reroutes vehicles in regular intervals. This causes direct routes (from an idle location) and diversions (changing the path of a moving vehicle).

If, by chance, a vehicle and a waiting request are on the same link, the vehicle will pick up the customer and perform the trip.

Furthermore, the example shows how to make use of travel time estimates from MATSim. Optionally, the dispatcher will request updated traversal times for all links in the network in a fixed interval (`travel_time_interval`) and then use the updated travel times to calculate shortest paths.

## Rejecting dispatcher

In `example/rejecting_dispatcher.py` the Euclidean/Bipartite dispatcher from above is extended with rejections: If a request is still not picked up once the `latestPickupTime` is exceeded, it is rejected.

## Q-learning dispatcher

In `example/qlearning_dispatcher.py` a simple Q-learning algorithm is implemented for relocating the vehicles while the assignment of requests is performed using Bipartite matching (see above). The example spans a rectangular grid with configurable size and resulting `N` cells over the network. Furthermore, unassigned vehicles are moved to a new destination in `T` time steps (every 30min, for instance). The state space of the vehicles is, hence, `N x T`. In every decision step, the vehicles decide to go to any of the `N` zones, so the action space is of size `N`. The resulting Q matrix is `N x T x N`. Each time an unassigned vehicle is sent for relocation, the reward is tracked until the next decision epoch. The reward is represented by the number of picked up after having received the relocation instruction. All rewards are integrated using the [standard definition](https://en.wikipedia.org/wiki/Q-learning).

## Communication interface

- Initialization: first message sent to MATSim to open the conversation

```json
{
    "@message": "initialization"
}
```

- Iteration: Sent by MATSim to indicate the start of a new iteration. The Initialization message is always answered by an Iteration message.

```json
{
    "@message": "iteration",
    "vehicles": [
        { "id": "veh1", "startLink": "123123", "capacity": 4 },
        { "id": "veh1", "startLink": "123123", "capacity": 4 }
    ]
}
```

At the beginning of each iteration, the initial state of all vehicles is transmitted.

- Assignment: The dispatcher answers to Iteration and State (see below) with this message:

```json
{
    "@message": "assignment",
    "stops": {
        "veh1": [
            { 
                "link": "123", 
                "pickup": ["req1"], 
                "earliestStartTime": 0.0, 
                "stopDuration": 30.0 
            },
            { 
                "link": "5242", 
                "dropoff": ["req1"], 
                "stopDuration": 30.0 
            },
        ],
        "veh2": [
            {
                "link": "12412",
                "route": ["123", "1252", "125", "55"]
            }
        ]
    },
    "rejections": [
        "req5", "req3"
    ]
}
```

The assignment is a list of vehicles that are *updated* (all vehicles that are not mentioned follow their previously defined schedule). For the updated vehicles, a sequence of stops is given. Those stops can be pickup/dropoff stops (if a list of respective request identifiers is given) or simply cause relocation of the vehicle. Pickup/Dropoff stops *can* have an `earliestStartTime` (for prebooked requests), but *must* have a duration. 

For each stop a sequence of links *can* be given such that the vehicle will follow that route. Note that the route needs to start with the link that is given as the `divergeLink`. If no route is given, MATSim will calculate the shortest path *automatically*. 

A list of rejected requests can be provided that are from that point on removed from the system.

- State: The assignment is answered by a *State* after the next simulation step has been performed:

```json
{
    "@message": "state",
    "time": 7200.0,
    "pickedUp": { "req1": "veh1" },
    "droppedOff": { "req5": "veh10", "req7": "veh12" },
    "vehicles": [
        { 
            "id": "veh1", 
            "currentLink": "512", 
            "curentExitTime": 7201.0, 
            "divergeLink": "513", 
            "divergeTime": 7211.0, 
            "state": "drive" 
        }
    ],
    "submitted": [
        { 
            "id": "req55",  
            "originLink": "5521",
            "destinationLink": "8573",
            "earliestPickupTime": 0.0,
            "latestPickupTime": 7300.0,
            "latestArrivalTime": 8000.0,
            "size": 1
        }
    ]
}
```

First, a map of picked up and dropped off requests is given, including the vehicle that performed that action. Second, a list of vehicle states is given with their current location and the earliest point and time when the route of the vehicle can be diverted. Third, a list of *newly* submitted requests is given including their time constraints, origin, and destination.

- Finalization: Once the simulation is over, MATSim will send this message:

```json
{
    "@message": "finalization"
}
```

- Travel time query: Current link travel time estimates in the network can be requested as follows:

```json
{
    "@message": "travel_time_query",
    "links": ["link1", "link2"]
}
```

If an empty list is given, the travel times on *all* links of the network will be returned. If a list of link identifiers (as *str*) is given, only travel times for those links are returned.

- Travel time response: This message is returned for a travel time query. It contains a map of the link id and the current estimate of the traversal time:

```json
{
    "@message": "travel_time_response",
    "travelTimes": { "link1": 54.25, "link2": 12.55 }
}
```
