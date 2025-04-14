# MATSim / FleetPy bridge

## Example MATSim simulation

How to run the MATSim test simulation:

- You need to have Java installed an be able to run the run scripts (`Run*`) using your IDE. You IDE should manage the dependencies for Maven/Java. This should work out of the box in VSCode, IntelliJ or Eclipse if you import the present project as a Maven project.
- As input data, only a network for Paris in MATSim format is provided in `scenario/network.xml.gz`.
- To run a simulation, you first need to generate some dmeand in `scenario/demand.xml`. You can do so by running the `RunCreateDemand` script with the following command line arguments (for 10,000 requests with randomly distributed origins and destination around with a mean departure time of 10am and a standard deviation of 2 hours):

```bash
--network-path /path/to/scenario/network.xml.gz
--output-path /path/to/scenario/demand.xml
--requests 10000
```

- Second, you need to create a fleet in `scenario/demfleetand.xml`. You can do so by running the `RunCreateFleet` script with the following command line arguments (for 100 vehicles randomly distributed in the network):

```bash
--network-path /path/to/scenario/network.xml.gz
--output-path /path/to/scenario/fleet.xml
--vehicles 100 --seats 4
```

- Finally, you can run the MATSim simulation using `RunSimulation`:

```bash
--network-path /path/to/scenario/network.xml.gz
--fleet-path /path/to/scenario/fleet.xml
--demand-path /path/to/scenario/demand.xml
--output-path /path/to/scenario/output
```

- You can then find the outputs in `scenario/output`. Have a look, for instance, at `scenario/output/ITERS/it.0/0.occupancy_time_profiles_StackedArea_drt.png` which shows you the occupancy of the vehicles throghout the simulation.
