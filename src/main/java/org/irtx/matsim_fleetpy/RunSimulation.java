package org.irtx.matsim_fleetpy;

import java.io.IOException;
import java.util.Random;

import org.irtx.matsim_fleetpy.bridge.FleetPyModule;
import org.irtx.matsim_fleetpy.bridge.FleetPyQSimModule;
import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.DrtInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup.EndtimeInterpretation;
import org.matsim.core.config.groups.QSimConfigGroup.StarttimeInterpretation;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.PrepareForSim;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultSelector;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.inject.Inject;
import com.google.inject.Provider;

public class RunSimulation {
	static public void main(String[] args) throws ConfigurationException, JsonGenerationException, JsonMappingException,
			IOException, InterruptedException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("demand-path", "fleet-path", "network-path", "output-path") //
				.allowOptions("threads", "remote-port", "update-demand") //
				.build();

		Config config = ConfigUtils.createConfig(new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup());

		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(100);

		config.plans().setInputFile(cmd.getOptionStrict("demand-path"));
		config.controller().setOutputDirectory(cmd.getOptionStrict("output-path"));
		config.network().setInputFile(cmd.getOptionStrict("network-path"));

		config.qsim().setFlowCapFactor(1e9);
		config.qsim().setStorageCapFactor(1e9);
		config.qsim().setSimStarttimeInterpretation(StarttimeInterpretation.onlyUseStarttime);
		config.qsim().setEndTime(24.0 * 3600.0);
		config.qsim().setSimEndtimeInterpretation(EndtimeInterpretation.onlyUseEndtime);

		StrategySettings strategy = new StrategySettings();
		strategy.setStrategyName(DefaultSelector.KeepLastSelected);
		strategy.setWeight(1.0);
		config.replanning().addStrategySettings(strategy);

		int threads = cmd.getOption("threads").map(Integer::parseInt)
				.orElse(Runtime.getRuntime().availableProcessors());
		config.global().setNumberOfThreads(threads);
		config.qsim().setNumberOfThreads(threads);

		ModeParams drtParams = new ModeParams("drt");
		config.scoring().addModeParams(drtParams);

		ActivityParams activityParams = new ActivityParams("generic");
		activityParams.setScoringThisActivityAtAll(false);
		config.scoring().addActivityParams(activityParams);

		DvrpConfigGroup dvrpConfig = DvrpConfigGroup.get(config);

		SquareGridZoneSystemParams grid = new SquareGridZoneSystemParams();
		grid.setCellSize(400);

		dvrpConfig.getTravelTimeMatrixParams().addParameterSet(grid);
		dvrpConfig.getTravelTimeMatrixParams().setMaxNeighborDistance(0);

		DrtConfigGroup drtConfig = new DrtConfigGroup();
		MultiModeDrtConfigGroup.get(config).addParameterSet(drtConfig);

		drtConfig.setVehiclesFile(cmd.getOptionStrict("fleet-path"));
		drtConfig.setStopDuration(60.0);

		DrtInsertionSearchParams searchParams = new ExtensiveInsertionSearchParams();
		drtConfig.addParameterSet(searchParams);

		DrtOptimizationConstraintsSetImpl constraints = drtConfig
				.addOrGetDrtOptimizationConstraintsParams()
				.addOrGetDefaultDrtOptimizationConstraintsSet();

		constraints.setMaxWaitTime(300.0);
		constraints.setMaxTravelTimeAlpha(1.5);
		constraints.setMaxTravelTimeBeta(300.0);

		DrtConfigs.adjustMultiModeDrtConfig(MultiModeDrtConfigGroup.get(config), config.scoring(), config.routing());

		Scenario scenario = ScenarioUtils.createScenario(config);

		scenario.getPopulation()
				.getFactory()
				.getRouteFactories()
				.setRouteFactory(DrtRoute.class, new DrtRouteFactory());

		ScenarioUtils.loadScenario(scenario);

		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new DvrpModule());
		controller.addOverridingModule(new MultiModeDrtModule());
		controller.configureQSimComponents(DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(config)));

		if (cmd.hasOption("remote-port")) {
			int remotePort = Integer.parseInt(cmd.getOptionStrict("remote-port"));
			controller.addOverridingModule(new FleetPyModule("drt", remotePort));
			controller.addOverridingQSimModule(new FleetPyQSimModule("drt"));
		}

		controller.addOverridingModule(new AbstractDvrpModeModule("drt") {
			@Override
			public void install() {
				bindModal(TravelTime.class).toInstance(new QSimFreeSpeedTravelTime(config.qsim()));
			}
		});

		boolean updateDemand = cmd.getOption("update-demand").map(Boolean::parseBoolean).orElse(false);
		if (updateDemand) {
			Updater updater = new Updater(scenario.getPopulation(), scenario.getNetwork());

			controller.addOverridingModule(new AbstractDvrpModeModule("drt") {
				@Override
				public void install() {
					bindModal(FleetSpecification.class).toProvider(modalProvider(getter -> {
						return updater.fleet;
					}));

					addControlerListenerBinding().toProvider(new Provider<UpdateListener>() {
						@Inject
						PrepareForSim prepare;

						@Override
						public UpdateListener get() {
							return new UpdateListener(updater, prepare);
						}
					});
				}
			});
		}

		controller.run();
	}

	static private class UpdateListener implements IterationEndsListener {
		private final Updater updater;
		private final PrepareForSim prepare;

		public UpdateListener(Updater updater, PrepareForSim prepare) {
			this.updater = updater;
			this.prepare = prepare;
		}

		@Override
		public void notifyIterationEnds(IterationEndsEvent event) {
			updater.update();
			prepare.run();
		}
	}

	static private class Updater {
		private final Random random = new Random(0);

		private final Population population;
		private final Network network;
		private FleetSpecification fleet;

		public Updater(Population population, Network network) {
			this.population = population;
			this.network = network;

			update();
		}

		public void update() {
			IdSet<Person> all = new IdSet<>(Person.class);
			all.addAll(population.getPersons().keySet());
			all.forEach(population::removePerson);

			RunCreateDemand.apply(population, network, random, 1500, 2);

			fleet = RunCreateFleet.apply(network, random, 40, 2);
		}
	}
}
