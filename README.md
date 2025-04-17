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

This will make MATSim listen on that port for the remote dispatcher. Communication is performed using the *ZeroMQ* library that is available in various languages. An example dispatcher is given in `example/random.py`. What it does is the following:

- It connects to MATSim on the port that is written in the script using *ZMQ*.
- It gives an initial ping to MATSim.
- It receives an initialization message for a new iteration, including the initial list of vehicles and their locations in the network.

In every subsequent step the script answsers with an *assignment* message (the first one is empty) and receives a *state* message.

- The script collects all submitted requests in a list that are coming in in every step.
- The script keeps a list of available vehicles (initially all of them).
- In every step, the script takes the first available vehilce of the list and the oldest request, and sets up a matching between the two. The vehicle is removed from the available list.
- As soon as the *state* indicates that a dropoff happened, the vehicle is added back tp the list of available vehicles.

In essence, this is a completely random unit capacity dispatcher that always matches randomly one vehicle with one request. As soon as the request is dropped off, another one can be assigned.

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
    }
}
```

The assignment is a list of vehicles that are *updated* (all vehicles that are not mentioned follow their previously defined schedule). For the updated vehicles, a sequence of stops is given. Those stops can be pickup/dropoff stops (if a list of respective request identifiers is given) or simply cause relocation of the vehicle. Pickup/Dropoff stops *can* have an `earliestStartTime` (for prebooked requests), but *must* have a duration. 

For each stop a sequence of links *can* be given such that the vehicle will follow that route (NOT IMPLEMENTED YET). If no route is given, MATSim will calculate the shortest path *automatically*.

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
