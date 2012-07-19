package fi.aalto.maximapool;

import java.io.File;
import java.util.Properties;

public class MaximaProcessConfig {
	public int startupLimit = 20;
	public boolean fileHandling = false;
	public String pathCommandTemplate = "TMP_IMG_DIR: \"%WORK-DIR%\"; IMG_DIR: \"%OUTPUT-DIR%\"";
	public String killString = "--COMPLETED--kill--PROCESS--";
	public String cmdLine = "maxima";
	public File cwd = new File(".");
	public File load = null;
	public String loadReady = "(%i1)";
	public String useReady = "(%i1)";
	public long executionTime = 30000;
	public long lifeTime = 60000000;

	public MaximaProcessConfig() {}

	public MaximaProcessConfig(Properties properties) {
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

		executionTime = Long.parseLong(properties.getProperty(
				"pool.execution.time.limit", "" + executionTime));
		lifeTime = Long.parseLong(properties.getProperty(
				"pool.process.lifetime", "" + lifeTime));
	}
}