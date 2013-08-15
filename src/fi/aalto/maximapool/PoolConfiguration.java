package fi.aalto.maximapool;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Stores all the configuration for how the PoolCoordingtor should work.
 */
class PoolConfiguration {

	/**
	 * The root directory that contains the different process types that can be run.
	 */
	File directoryRoot;

	/**
	 * Lower limit to the number of processes we keep in the pool, irrespective
	 * of the demand estimate
	 */
	int minimumAvailableProcesses = 5;

	/**
	 * Upper limit to the number of processes we keep in the pool, irrespective
	 * of the demand estimate
	 */
	int maximumAvailableProcesses = 100;

	/**
	 * Maximum number of processes we allow to be in the process of starting at
	 * any one time.
	 */
	int startupLimit = 100;

	/**
	 * Delay (ms) between runs of the maintenance tasks.
	 */
	long maintenanceCycleTime = 500;

	/**
	 * Number of data values to use in the rolling averages.
	 */
	int movingAverageDataPoints = 5;

	/**
	 * If we estimate, based on the current load, that we need N processes
	 * in the pool, we actually keep 3*N (providing this is less than poolMax).
	 */
	double safetyMultiplier = 3.0;

	/**
	 * The configuration for the processes we create.
	 */
	Map<String, ProcessConfiguration> processConfigurations = new LinkedHashMap<String, ProcessConfiguration>();

	/**
	 * Update the configuration using any values in a set of properties.
	 * @param properties the Properties to read.
	 */
	void loadProperties(Properties properties) {
		minimumAvailableProcesses = Integer.parseInt(properties.getProperty(
				"size.min", "" + minimumAvailableProcesses));
		maximumAvailableProcesses = Integer.parseInt(properties.getProperty(
				"size.max", "" + maximumAvailableProcesses));
		startupLimit = Integer.parseInt(properties.getProperty(
				"start.limit", "" + startupLimit));
		maintenanceCycleTime = Long.parseLong(properties.getProperty(
				"update.cycle", "500"));
		movingAverageDataPoints = Integer.parseInt(properties.getProperty(
				"adaptation.averages.length", "" + movingAverageDataPoints));
		safetyMultiplier = Double.parseDouble(properties.getProperty(
				"adaptation.safety.multiplier", "" + safetyMultiplier));
	}

	/**
	 * Describe the values of all the settings in a way that can be shown to users.
	 * @return a hash map where the keys are human-readable names for the settings,
	 * and the values are string representations of the current values.
	 */
	Map<String, String> describe() {

		Map<String, String> values = new LinkedHashMap<String, String>();

		values.put("Root directory", directoryRoot.getAbsolutePath());
		values.put("Min pool size", "" + minimumAvailableProcesses);
		values.put("Max pool size", "" + maximumAvailableProcesses);
		values.put("Limit on number of processes starting up", "" + startupLimit);
		values.put("Maintenance cycle time", maintenanceCycleTime + " ms");
		values.put("Number of data points for averages", "" + movingAverageDataPoints);
		values.put("Pool size safety multiplier", "" + safetyMultiplier);

		return values;
	}

	/**
	 * Look on disc for all the child directories of directoryRoot, to find the
	 * processes configurations that we can run.
	 */
	void scanAvailableProcessConfigurations() {
		Map<String, ProcessConfiguration> configurations = new LinkedHashMap<String, ProcessConfiguration>();
		for (File subdirectory : directoryRoot.listFiles()) {
			if (!subdirectory.isDirectory()) {
				continue;
			}
			File configFile = new File(subdirectory, "process.conf");
			if (!configFile.isFile()) {
				continue;
			}

			Properties properties = new Properties();
			try {
				FileReader reader = new FileReader(configFile);
				properties.load(reader);
				reader.close();
			} catch (IOException ioe) {
				continue;
			}

			if (!subdirectory.getName().equals(properties.getProperty("name"))) {
				continue;
			}

			ProcessConfiguration configuration = new ProcessConfiguration();
			configuration.loadProperties(properties);
			configurations.put(subdirectory.getName(), configuration);
		}

		// It is intentional that we build the complete new array, and then swap
		// it into place in a single operation.
		processConfigurations = configurations;
	}
}
