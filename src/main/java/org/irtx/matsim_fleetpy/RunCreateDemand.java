package org.irtx.matsim_fleetpy;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.utils.geometry.CoordUtils;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class RunCreateDemand {
	static public void apply(Population population, Network network, Random random, int requests,
			double minimumDistance) {
		PopulationFactory factory = population.getFactory();

		List<Id<Link>> linkIds = new LinkedList<>(network.getLinks().keySet());
		Collections.sort(linkIds);

		Random networkRandom = new Random(0);

		List<Double> originCDF = new LinkedList<>();
		double originTotal = 0.0;

		List<Double> destinationCDF = new LinkedList<>();
		double destinationTotal = 0.0;

		for (int k = 0; k < linkIds.size(); k++) {
			originTotal += networkRandom.nextDouble();
			destinationTotal += networkRandom.nextDouble();

			originCDF.add(originTotal);
			destinationCDF.add(destinationTotal);
		}

		for (int k = 0; k < linkIds.size(); k++) {
			originCDF.set(k, originCDF.get(k) / originTotal);
			destinationCDF.set(k, destinationCDF.get(k) / destinationTotal);
		}

		for (int i = 0; i < requests; i++) {
			Id<Link> originLinkId;
			Id<Link> destinationLinkId;
			double distance = 0.0;

			do {
				double originU = random.nextDouble();
				int originIndex = (int) originCDF.stream().filter(cdf -> originU > cdf).count();

				double destinationU = random.nextDouble();
				int destinationIndex = (int) originCDF.stream().filter(cdf -> destinationU > cdf).count();

				originLinkId = linkIds.get(originIndex);
				destinationLinkId = linkIds.get(destinationIndex);

				Link originLink = network.getLinks().get(originLinkId);
				Link destinationLink = network.getLinks().get(destinationLinkId);

				distance = CoordUtils.calcEuclideanDistance(originLink.getCoord(), destinationLink.getCoord());
			} while (distance < minimumDistance);

			double departureTime1 = random.nextGaussian(8.0 * 3600.0, 2.0 * 3600.0);
			double departureTime2 = random.nextGaussian(17.0 * 3600.0, 2.0 * 3600.0);
			double departureTime = random.nextBoolean() ? departureTime1 : departureTime2;

			Person person = factory.createPerson(Id.createPersonId("request_" + i));
			population.addPerson(person);

			Plan plan = factory.createPlan();
			person.addPlan(plan);

			Activity originActivity = factory.createActivityFromLinkId("generic", originLinkId);
			originActivity.setEndTime(departureTime);
			plan.addActivity(originActivity);

			Leg leg = factory.createLeg("drt");
			plan.addLeg(leg);

			Activity destinationActivity = factory.createActivityFromLinkId("generic", destinationLinkId);
			plan.addActivity(destinationActivity);
		}
	}

	static public void main(String[] args) throws ConfigurationException, JsonGenerationException, JsonMappingException,
			IOException, InterruptedException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("network-path", "output-path", "requests") //
				.allowOptions("seed", "minimum-distance") //
				.build();

		int seed = cmd.getOption("seed").map(Integer::parseInt).orElse(0);
		int requests = Integer.parseInt(cmd.getOptionStrict("requests"));
		double minimumDistance = cmd.getOption("minimum-distance").map(Double::parseDouble).orElse(1000.0);

		Network fullNetwork = NetworkUtils.createNetwork();
		new MatsimNetworkReader(fullNetwork).readFile(cmd.getOptionStrict("network-path"));

		Network network = NetworkUtils.createNetwork();
		new TransportModeNetworkFilter(fullNetwork).filter(network, Collections.singleton("car"));

		Config config = ConfigUtils.createConfig();
		Population population = PopulationUtils.createPopulation(config);

		Random random = new Random(seed);

		apply(population, network, random, requests, minimumDistance);
		new PopulationWriter(population).write(cmd.getOptionStrict("output-path"));
	}
}
