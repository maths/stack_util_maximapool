package fi.aalto.maximapool;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Stores all the configuration need to start a MaximaProcess.
 */
class ProcessConfiguration {

	/**
	 * Whether a pool for this type of process shoudl be automatically started
	 * when the server starts.
	 */
	boolean autoStart = false;

	/**
	 * The folder to use at the current working directory for the process.
	 */
	File workingDirectory = new File(".");

	/**
	 * The command line to use to start a process.
	 */
	String commandLine = "maxima-optimised";

	/**
	 * Environment values to define.
         */
	Map<String, String> environment = new LinkedHashMap<String, String>();

	/**
	 * The output to look for so we know the process is ready to receive the
	 * load command. Only used if load is set.
	 */
	String processHasStartedOutput = "(%i1)";

	/**
	 * The file to load using the command "load(...);" once loadReady is seen.
	 */
	File extraFileToLoad = null;

	/**
	 * The output to look for so we know the process is ready for use.
	 */
	String processIsReadyOutput = "(%i1)";

	/**
	 * We append code to the end of the Maxima command so that it outputs this
	 * string once all the other processing is done. We use this to be sure
	 * we have captured all the output.
	 */
	String killString = "--COMPLETED--kill--PROCESS--";

	/**
	 * Are we doing file handling?
	 */
	boolean fileHandling = false;

	/**
	 * If we are doing file handling, this template gives the command to send to
	 * Maxima to tell it the paths to use.
	 */
	String pathCommandTemplate = "TMP_IMG_DIR: \"%WORK-DIR%\"; IMG_DIR: \"%OUTPUT-DIR%\"";

	/**
	 * The timeout (ms) to use when starting processes. If a process takes longer
	 * than this to become ready, it is killed.
	 */
	long startupTimeout = 10000;

	/**
	 * The timeout (ms) for how long a process will be kept after it is ready
	 * and before it is used. If the process becomes older than this, it is
	 * killed a a new one is launched.
	 */
	long maximumLifetime = 60000000;

	/**
	 * The additional timeout (ms) that is added to lifeTime once a process
	 * receives a command. Avoids problems where a processe receives a command
	 * just before lifeTime runs out.
	 */
	long executionTimeout = 30000;

	/**
	 * Initial estimate for the start-up time (ms) for a process.
	 */
	long startupTimeInitialEstimate = 2000;

	/**
	 * Initial estimate for the frequency (Hz) with which a process is required.
	 */
	double demandInitialEstimate = 0.001;

	/**
	 * Update the configuration using any values in a set of properties.
	 * @param properties the Properties to read.
	 */
	void loadProperties(Properties properties) {
		autoStart = "true".equals(properties.getProperty("auto.start", autoStart ? "true" : "false"));

		workingDirectory = new File(properties.getProperty("working.directory", "."));
		commandLine = properties.getProperty("command.line", commandLine);
		processHasStartedOutput = properties.getProperty("process.started", processHasStartedOutput);
		extraFileToLoad = new File(properties.getProperty("extra.file", "false"));
		if (extraFileToLoad.getName().equals("false")) {
			extraFileToLoad = null;
		}
		processIsReadyOutput = properties.getProperty("process.ready", processIsReadyOutput);

		fileHandling = properties.getProperty("file.handling", "false").equalsIgnoreCase("true");
		pathCommandTemplate = properties.getProperty("path.command", pathCommandTemplate);

		startupTimeout = Long.parseLong(properties.getProperty(
				"startup.timeout", "" + startupTimeout));
		executionTimeout = Long.parseLong(properties.getProperty(
				"execution.timeout", "" + executionTimeout));
		maximumLifetime = Long.parseLong(properties.getProperty(
				"maximum.lifetime", "" + maximumLifetime));

		startupTimeInitialEstimate = Long.parseLong(properties.getProperty(
				"startup.time.estimate", "" + startupTimeInitialEstimate));
		demandInitialEstimate = Double.parseDouble(properties.getProperty(
				"demand.estimate", "" + demandInitialEstimate));

		for (Enumeration e = properties.propertyNames(); e.hasMoreElements();) {
			String key = e.nextElement().toString();
			if (key.startsWith("env.") && key.length() > 4) {
				environment.put(key.substring(4), properties.getProperty(key, ""));
			}
		}
	}

	/**
	 * Describe the values of all the settings in a way that can be shown to users.
	 * @return a hash map where the keys are human-readable names for the settings,
	 * and the values are string representations of the current values.
	 */
	Map<String, String> describe() {

		Map<String, String> values = new LinkedHashMap<String, String>();

		values.put("Maxima command-line", commandLine);
		if (extraFileToLoad != null) {
			try {
				values.put("File to load", extraFileToLoad.getCanonicalPath());
			} catch (IOException e) {
			}
		}
		values.put("Started test string", processHasStartedOutput);
		values.put("Loaded test string", processIsReadyOutput);
		values.put("File handling", fileHandling ? "On" : "Off");
		values.put("File paths template", pathCommandTemplate);
		values.put("Startup time limit", startupTimeout + " ms");
		values.put("Execution extra time limit", executionTimeout + " ms");
		values.put("Process life time limit", maximumLifetime + " ms");
		values.put("Initial estimate for the process startup time", startupTimeInitialEstimate + " ms");
		values.put("Initial estimate for demand", demandInitialEstimate + " Hz");

		return values;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (autoStart ? 1231 : 1237);
		result = prime * result + ((commandLine == null) ? 0 : commandLine.hashCode());
		long temp;
		temp = Double.doubleToLongBits(demandInitialEstimate);
		result = prime * result + (int) (temp ^ (temp >>> 32));
		result = prime * result + (int) (executionTimeout ^ (executionTimeout >>> 32));
		result = prime * result + ((extraFileToLoad == null) ? 0 : extraFileToLoad.hashCode());
		result = prime * result + (fileHandling ? 1231 : 1237);
		result = prime * result + killString.hashCode();
		result = prime * result + (int) (maximumLifetime ^ (maximumLifetime >>> 32));
		result = prime * result + ((pathCommandTemplate == null) ? 0 : pathCommandTemplate.hashCode());
		result = prime * result + processHasStartedOutput.hashCode();
		result = prime * result + ((processIsReadyOutput == null) ? 0 : processIsReadyOutput.hashCode());
		result = prime * result + (int) (startupTimeInitialEstimate ^ (startupTimeInitialEstimate >>> 32));
		result = prime * result + (int) (startupTimeout ^ (startupTimeout >>> 32));
		result = prime * result + workingDirectory.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}

		ProcessConfiguration other = (ProcessConfiguration) obj;
		if (autoStart != other.autoStart) {
			return false;
		}
		if (commandLine == null) {
			if (other.commandLine != null) {
				return false;
			}
		} else if (!commandLine.equals(other.commandLine)) {
			return false;
		}
		if (Double.doubleToLongBits(demandInitialEstimate) !=
				Double.doubleToLongBits(other.demandInitialEstimate)) {
			return false;
		}
		if (executionTimeout != other.executionTimeout) {
			return false;
		}
		if (extraFileToLoad == null) {
			if (other.extraFileToLoad != null) {
				return false;
			}
		} else if (!extraFileToLoad.equals(other.extraFileToLoad)) {
			return false;
		}
		if (fileHandling != other.fileHandling) {
			return false;
		}
		if (killString == null) {
			if (other.killString != null) {
				return false;
			}
		} else if (!killString.equals(other.killString)) {
			return false;
		}
		if (maximumLifetime != other.maximumLifetime) {
			return false;
		}
		if (pathCommandTemplate == null) {
			if (other.pathCommandTemplate != null) {
				return false;
			}
		} else if (!pathCommandTemplate.equals(other.pathCommandTemplate)) {
			return false;
		}
		if (processHasStartedOutput == null) {
			if (other.processHasStartedOutput != null) {
				return false;
			}
		} else if (!processHasStartedOutput.equals(other.processHasStartedOutput)) {
			return false;
		}
		if (processIsReadyOutput == null) {
			if (other.processIsReadyOutput != null) {
				return false;
			}
		} else if (!processIsReadyOutput.equals(other.processIsReadyOutput)) {
			return false;
		}
		if (startupTimeInitialEstimate != other.startupTimeInitialEstimate) {
			return false;
		}
		if (startupTimeout != other.startupTimeout) {
			return false;
		}
		if (workingDirectory == null) {
			if (other.workingDirectory != null) {
				return false;
			}
		} else if (!workingDirectory.equals(other.workingDirectory)) {
			return false;
		}
		return true;
	}
}
