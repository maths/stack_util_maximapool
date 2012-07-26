package fi.aalto.maximapool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Stores all the configuration need to start a MaximaProcess.
 */
class MaximaPoolConfig {

	/**
	 * Lower limit to the number of processes we keep in the pool, irrespective
	 * of the demand estimate
	 */
	int poolMin = 5;

	/**
	 * Upper limit to the number of processes we keep in the pool, irrespective
	 * of the demand estimate
	 */
	int poolMax = 100;

	/**
	 * Maximum number of processes we allow to be in the process of starting at
	 * any one time.
	 */
	int startupLimit = 100;

	/**
	 * Delay (ms) between runs of the maintenance tasks.
	 */
	long updateCycle = 500;

	/**
	 * Initial estimate for the start-up time (ms) for a process.
	 */
	long startupTimeInitialEstimate = 2000;

	/**
	 * Initial estimate for the frequency (Hz) with which a process is required.
	 */
	double demandInitialEstimate = 0.001;

	/**
	 * Number of data values to use in the rolling averages.
	 */
	int averageCount = 5;

	/**
	 * If we estimate, based on the current load, that we need N processes
	 * in the pool, we actually keep 3*N (providing this is less than poolMax).
	 */
	double safetyMultiplier = 3.0;

	/**
	 * Update the configuration using any values in a set of properties.
	 * @param properties the Properties to read.
	 */
	void loadProperties(Properties properties) {
		poolMin = Integer.parseInt(properties.getProperty(
				"pool.size.min", "" + poolMin));
		poolMax = Integer.parseInt(properties.getProperty(
				"pool.size.max", "" + poolMax));
		startupLimit = Integer.parseInt(properties.getProperty(
				"pool.start.limit", "" + startupLimit));
		updateCycle = Long.parseLong(properties.getProperty(
				"pool.update.cycle", "500"));
		startupTimeInitialEstimate = Long.parseLong(properties.getProperty(
				"pool.adaptation.startuptime.initial.estimate", "" + startupTimeInitialEstimate));
		demandInitialEstimate = Double.parseDouble(properties.getProperty(
				"pool.adaptation.demand.initial.estimate", "" + demandInitialEstimate));
		averageCount = Integer.parseInt(properties.getProperty(
				"pool.adaptation.averages.length", "" + averageCount));
		safetyMultiplier = Double.parseDouble(properties.getProperty(
				"pool.adaptation.safety.multiplier", "" + safetyMultiplier));
	}

	/**
	 * Describe the values of all the settings in a way that can be shown to users.
	 * @return a hash map where the keys are human-readable names for the settings,
	 * and the values are string representations of the current values.
	 */
	Map<String, String> describe() {

		Map<String, String> values = new LinkedHashMap<String, String>();

		values.put("Min pool size", "" + poolMin);
		values.put("Max pool size", "" + poolMax);
		values.put("Limit on number of processes starting up", "" + startupLimit);
		values.put("Maintenance cycle time", updateCycle + " ms");
		values.put("Number of data points for averages", "" + averageCount);
		values.put("Pool size safety multiplier", "" + safetyMultiplier);
		values.put("Initial estimate for the process startup time", startupTimeInitialEstimate + " ms");
		values.put("Initial estimate for demand", demandInitialEstimate + " Hz");

		return values;
	}
}
