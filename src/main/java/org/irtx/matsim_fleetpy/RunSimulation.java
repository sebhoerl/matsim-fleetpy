package org.irtx.matsim_fleetpy;

import java.io.IOException;

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.insertion.DrtInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup.StarttimeInterpretation;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class RunSimulation {
	static public void main(String[] args) throws ConfigurationException, JsonGenerationException, JsonMappingException,
			IOException, InterruptedException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("demand-path", "fleet-path", "network-path", "output-path") //
				.allowOptions("threads") //
				.build();

		Config config = ConfigUtils.createConfig(new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup());

		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);

		config.plans().setInputFile(cmd.getOptionStrict("demand-path"));
		config.controller().setOutputDirectory(cmd.getOptionStrict("output-path"));
		config.network().setInputFile(cmd.getOptionStrict("network-path"));

		config.qsim().setFlowCapFactor(1e9);
		config.qsim().setStorageCapFactor(1e9);
		config.qsim().setSimStarttimeInterpretation(StarttimeInterpretation.onlyUseStarttime);

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
		grid.cellSize = 400;

		dvrpConfig.getTravelTimeMatrixParams().addParameterSet(grid);
		dvrpConfig.getTravelTimeMatrixParams().maxNeighborDistance = 0;

		DrtConfigGroup drtConfig = new DrtConfigGroup();
		MultiModeDrtConfigGroup.get(config).addParameterSet(drtConfig);

		drtConfig.vehiclesFile = cmd.getOptionStrict("fleet-path");
		drtConfig.stopDuration = 60.0;

		DrtInsertionSearchParams searchParams = new ExtensiveInsertionSearchParams();
		drtConfig.addParameterSet(searchParams);

		DefaultDrtOptimizationConstraintsSet constraints = (DefaultDrtOptimizationConstraintsSet) drtConfig
				.addOrGetDrtOptimizationConstraintsParams()
				.addOrGetDefaultDrtOptimizationConstraintsSet();

		constraints.maxWaitTime = 300.0;
		constraints.maxTravelTimeAlpha = 1.5;
		constraints.maxTravelTimeBeta = 300.0;

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

		controller.run();
	}
}
