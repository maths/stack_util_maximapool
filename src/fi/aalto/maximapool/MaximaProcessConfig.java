package fi.aalto.maximapool;

import java.io.File;
import java.util.Properties;

/**
 * Stores all the configuration need to start a MaximaProcess.
 */
public class MaximaProcessConfig {

	/**
	 * The command line to use to start a process.
	 */
	public String cmdLine = "maxima";

	/**
	 * The folder to use at the current working directory for the process.
	 */
	public File cwd = new File(".");

	/**
	 * The output to look for so we know the process is ready to receive the
	 * load command. Only used if load is set.
	 */
	public String loadReady = "(%i1)";

	/**
	 * The file to load using the command "load(...);" once loadReady is seen.
	 */
	public File load = null;

	/**
	 * The output to look for so we know the process is ready for use.
	 */
	public String useReady = "(%i1)";

	/**
	 * We append code to the end of the Maxima command so that it outputs this
	 * string once all the other processing is done. We use this to be sure
	 * we have captured all the output.
	 */
	public String killString = "--COMPLETED--kill--PROCESS--";

	/**
	 * The maximum number of processes that are allowed to be starting at any
	 * one time.
	 */
	public int startupLimit = 20;

	/**
	 * The timeout (ms) to use when starting processes. If a process takes longer
	 * than this to become ready, it is killed.
	 */
	public long startupTime = 10000;

	/**
	 * The timeout (ms) for how long a process will be kept after it is ready
	 * and before it is used. If the process becomes older than this, it is
	 * killed a a new one is launched.
	 */
	public long lifeTime = 60000000;

	/**
	 * The additional timeout (ms) that is added to lifeTime once a process
	 * receives a command. Avoids problems where a processe receives a command
	 * just before lifeTime runs out.
	 */
	public long executionTime = 30000;

	/**
	 * Are we doing file handling?
	 */
	public boolean fileHandling = false;

	/**
	 * If we are doing file handling, this template gives the command to send to
	 * Maxima to tell it the paths to use.
	 */
	public String pathCommandTemplate = "TMP_IMG_DIR: \"%WORK-DIR%\"; IMG_DIR: \"%OUTPUT-DIR%\"";

	/**
	 * Update the configuration using any values in a set of properties.
	 * @param properties the Properties to read.
	 */
	public void loadProperties(Properties properties) {
		startupLimit = Integer.parseInt(properties.getProperty(
				"pool.start.limit", "" + startupLimit));
		cmdLine = properties.getProperty("maxima.commandline", cmdLine);
		loadReady = properties.getProperty("maxima.ready.for.load", loadReady);
		useReady = properties.getProperty("maxima.ready.for.use", useReady);
		cwd = new File(properties.getProperty("maxima.cwd", "."));
		load = new File(properties.getProperty("maxima.load", "false"));
		if (load.getName().equals("false")) {
			load = null;
		}

		fileHandling = properties.getProperty("file.handling", "false")
				.equalsIgnoreCase("true");
		pathCommandTemplate = properties.getProperty("maxima.path.command",
				pathCommandTemplate);

		startupTime = Long.parseLong(properties.getProperty(
				"pool.startup.time.limit", "" + startupTime));
		executionTime = Long.parseLong(properties.getProperty(
				"pool.execution.time.limit", "" + executionTime));
		lifeTime = Long.parseLong(properties.getProperty(
				"pool.process.lifetime", "" + lifeTime));
	}
}
