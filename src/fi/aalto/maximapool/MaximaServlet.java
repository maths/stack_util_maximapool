package fi.aalto.maximapool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import fi.aalto.utils.HtmlUtils;
import fi.aalto.utils.ReaderSucker;
import fi.aalto.utils.StringUtils;



/**
 * This servlet provides the public interface to the Maxima pools, and the
 * processes in them.
 *
 * @author Matti Harjula, Tim Hunt
 */
public class MaximaServlet extends HttpServlet {
	private static final long serialVersionUID = -8604075780786871066L;

	/**
	 * Timeput period used by the low-level healthcheck.
	 */
	private final static long HEALTHCHECK_TIMEOUT = 10000;

	/**
	 * Manages the different pools of processes running the different version
	 * of the Maxima code.
	 */
	private PoolCoordinator poolCoordinator;

	/**
	 * Records when the servlet started. (System.currentTimeMillis();)
	 */
	private long servletStartTime;

	/**
	 * The admin password. Must be typed in when doing any 'dangerous' operations.
	 */
	private String adminPassword;

	@Override
	public void init() throws ServletException {
		super.init();

		servletStartTime = System.currentTimeMillis();

		// Load properties.
		Properties properties = new Properties();
		try {
			properties.load(Thread.currentThread().getContextClassLoader()
					.getResourceAsStream("servlet.conf"));
		} catch (IOException ioe) {
			throw new ServletException("Cannot load servlet.conf.", ioe);
		}

		adminPassword = properties.getProperty("admin.password");
		if (adminPassword == null) {
			throw new ServletException("Admin password not set.");
		}

		File directoryRoot = new File(properties.getProperty("directory.root", ""));
		if (!directoryRoot.isDirectory()) {
			throw new ServletException("Configured directory.root (" +
					directoryRoot.getPath() + ") does not exist.");
		}

		PoolConfiguration poolConfiguration = new PoolConfiguration();
		poolConfiguration.directoryRoot = directoryRoot;

		File poolConf = new File(directoryRoot, "pool.conf");
		if (!poolConf.isFile()) {
			throw new ServletException("Configuation file " +
					poolConf.getPath() + " does not exist.");
		}

		try {
			FileReader reader = new FileReader(poolConf);
			properties.load(reader);
			reader.close();
		} catch (IOException ioe) {
			throw new ServletException("Failed to load configuation file " +
					poolConf.getPath(), ioe);
		}

		poolConfiguration.loadProperties(properties);
		poolConfiguration.scanAvailableProcessConfigurations();
		poolCoordinator = new PoolCoordinator(poolConfiguration);

		for (Map.Entry<String, ProcessConfiguration> entry :
				poolConfiguration.processConfigurations.entrySet()) {
			if (entry.getValue().autoStart) {
				poolCoordinator.startConfiguration(entry.getKey());
			}
		}
	}

	@Override
	public void destroy() {
		poolCoordinator.destroy();
		super.destroy();
	}

	@Override
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		try {
			// Dispatch the request.
			String healthcheck = request.getParameter("healthcheck");
			if ("1".equals(healthcheck)) {
				doHealthcheckLowLevel(request, response);

			} else if ("2".equals(healthcheck)) {
				doHealthcheckHighLevel(request, response);

			} else {
				doStatus(request, response);
			}

		} catch (Exception e) {
			HtmlUtils.sendErrorPage(response, e);
		}
	}


	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		request.setCharacterEncoding("UTF-8");
		try {
			if (request.getParameter("input") != null) {
				doProcess(request, response);
				return;
			}

			if (checkAdminPassword(request)) {
				if (request.getParameter("start") != null) {
					doStartPool(request);
				} else if (request.getParameter("stop") != null) {
					doStopPool(request);
				} else if (request.getParameter("scan") != null) {
					doScanConfigurations(request);
				}
			}
			response.sendRedirect("MaximaPool");

		} catch (Exception e) {
			HtmlUtils.sendErrorPage(response, e);
		}
	}

	/**
	 * Process a request that asks Maxima to calculate something.
	 * @param request the request.
	 * @param response the response to send.
	 * @throws IOException
	 */
	private void doProcess(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setCharacterEncoding("UTF-8");
		String theInput = request.getParameter("input");
		String configurationName = request.getParameter("version");
		long timeLimit = getRequestLong(request,"timeout", 3000);
		String plotUrlBase = getRequestString(request,"ploturlbase", "");

		// NOTE! the obvious lack of input sanity checks... so think where you
		// use this.
		MaximaProcess maximaProcess = poolCoordinator.getProcess(configurationName);
		if (maximaProcess.doAndDie(theInput, timeLimit, plotUrlBase)) {
			response.setStatus(HttpServletResponse.SC_OK);
		} else {
			// Send a specific message to the STACK question type that the CAS
			// calculation timed out.
			// This HTTP status code (Requested Range not satisfiable) is definitely
			// not 'right' since are not doing HTTP byte-serving with a Range header.
			// but unfortuately there is no status code that means what we want.
			// Therefore, we this 4xx status code that will not otherwise be used
			// to singnal this case to STACK.
			response.setStatus(416);
		}

		String out = maximaProcess.getOutput();

		if (maximaProcess.filesGenerated().size() > 0) {
			response.setContentType("application/zip");
			ZipOutputStream zos = new ZipOutputStream(response.getOutputStream());

			ZipEntry z = new ZipEntry("OUTPUT");
			zos.putNextEntry(z);
			zos.write(out.getBytes());
			zos.closeEntry();
			maximaProcess.addGeneratedFilesToZip(zos);
			zos.finish();

		} else {
			response.setContentType("text/plain");
			response.getWriter().write(out);
		}

		poolCoordinator.notifyProcessFinishedWith(maximaProcess);
	}

	/**
	 * Process a request to start a pool for a particular version of the Maxima code.
	 * @param request the request.
	 * @param response the response to send.
	 * @throws IOException
	 */
	private void doStartPool(HttpServletRequest request) throws ServletException, IOException {

		String configurationName = request.getParameter("start");
		poolCoordinator.startConfiguration(configurationName);
	}

	/**
	 * Process a request to stop the pool for a particular version of the Maxima code.
	 * @param request the request.
	 * @param response the response to send.
	 * @throws IOException
	 */
	private void doStopPool(HttpServletRequest request) throws ServletException, IOException {

		String configurationName = request.getParameter("stop");
		poolCoordinator.stopConfiguration(configurationName);
	}

	/**
	 * Process a request to stop the pool for a particular version of the Maxima code.
	 * @param request the request.
	 * @param response the response to send.
	 * @throws IOException
	 */
	private void doScanConfigurations(HttpServletRequest request) throws ServletException, IOException {

		poolCoordinator.scanConfigurations();
	}

	/**
	 * Display the current status of the servlet, with a form that can be used
	 * for testing, and with controls to manage which pools are running.
	 * @param request the request.
	 * @param response the response to send.
	 * @throws ServletException
	 * @throws IOException
	 */
	private void doStatus(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		List<String> runningPools = poolCoordinator.getRunningConfigurations();
		String[][] poolOptions = new String[runningPools.size()][2];
		String[][] timoutOptions = new String[][] {
				{"1000", "1 second"},
				{"2000", "2 seconds"},
				{"5000", "5 seconds"},
				{"10000", "10 seconds"},
				{"20000", "20 seconds"}};
		int i = 0;
		for (String configurationName : runningPools) {
			poolOptions[i][0] = configurationName;
			poolOptions[i][1] = configurationName;
			i++;
		}

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html");

		PrintWriter out = HtmlUtils.startOutput(response, "status display");

		HtmlUtils.writeHeading(out, "System performance");
		HtmlUtils.writeMapAsTable(out, getSystemPerformance());

		HtmlUtils.writeHeading(out, "Overall pool performance");
		HtmlUtils.writeMapAsTable(out, poolCoordinator.getStatus());

		HtmlUtils.writeHeading(out, "Running versions");

		for (String configurationName : runningPools) {
			HtmlUtils.writeDivStart(out, "pool", configurationName);
			HtmlUtils.writeSubHeading(out, "Pool performance - " + configurationName);
			HtmlUtils.writeMapAsTable(out, poolCoordinator.getPoolStatus(configurationName));

			if (!poolCoordinator.isConfigurationCurrent(configurationName)) {
				HtmlUtils.writeWarning(out, "The configuration has changed on disc since this pool was started. You should probably stop and then re-start this pool.");
			}

			HtmlUtils.writeLink(out, "?healthcheck=2&version=" + configurationName,
					"Run the high-level health-check");
			HtmlUtils.writeLink(out, "?healthcheck=1&version=" + configurationName,
					"Run the low-level health-check");
			HtmlUtils.writeActionButton(out, "stop", configurationName,
					"Stop this pool");

			HtmlUtils.writeSubHeading(out, "Pool configuration - " + configurationName);
			HtmlUtils.writeMapAsTable(out, poolCoordinator.getProcessConfiguration(configurationName).describe());
			HtmlUtils.writeDivEnd(out);
		}

		HtmlUtils.writeHeading(out, "Test form");
		HtmlUtils.writeFormStart(out);
		HtmlUtils.writeTextarea(out, "Input something for evaluation. (It must end in a ';'.)", "input", "1+1;");
		HtmlUtils.writeSelect(out, "Timeout", "timeout", timoutOptions);
		HtmlUtils.writeSelect(out, "Pool", "version", poolOptions);
		HtmlUtils.writeFormFinish(out, "Evaluate");

		HtmlUtils.writeHeading(out, "Overall pool configuration");
		HtmlUtils.writeMapAsTable(out, poolCoordinator.describeConfiguration());

		HtmlUtils.writeHeading(out, "Non-running versions");
		for (Map.Entry<String, ProcessConfiguration> entry :
				poolCoordinator.getAvailablePoolConfigurations().entrySet()) {
			String configurationName = entry.getKey();
			if (runningPools.contains(configurationName)) {
				continue;
			}

			HtmlUtils.writeDivStart(out, "pool", configurationName);
			HtmlUtils.writeSubHeading(out, "Stopped pool configuration - " + configurationName);
			HtmlUtils.writeMapAsTable(out, entry.getValue().describe());
			HtmlUtils.writeLink(out, "?healthcheck=1&version=" + configurationName,
					"Run the low-level health-check");
			HtmlUtils.writeActionButton(out, "start", configurationName,
					"Start this pool");
			HtmlUtils.writeDivEnd(out);
		}

		HtmlUtils.writeHeading(out, "Check for new pool definitions");
		HtmlUtils.writeActionButton(out, "scan", "", "Re-load pool definition");

		HtmlUtils.finishOutput(out);
	}

	/**
	 * This is a high-level healthcheck script which gets Maxima to do something,
	 * using the MaximaProcess class, but it run's synchonously, rather than using
	 * the pool.
	 * @param request the request.
	 * @param response the response to send.
	 * @throws IOException
	 * @throws ServletException
	 */
	private void doHealthcheckHighLevel(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		Writer out = HtmlUtils.startOutput(response, "high-level health-check");

		long startTime = System.currentTimeMillis();
		MaximaProcess maximaProcess = poolCoordinator.makeProcess(request.getParameter("version"));
		String firstOutput = maximaProcess.getOutput();
		HtmlUtils.writePre(out, firstOutput);
		out.flush();

		HtmlUtils.writeParagraph(out, "Sending command: '1+1;'.");

		maximaProcess.doAndDie("1+1;\n", 10000, "");
		String secondOutput = maximaProcess.getOutput();
		HtmlUtils.writePre(out, secondOutput.substring(firstOutput.length()));
		out.flush();

		HtmlUtils.writeParagraph(out, "Time taken: " + (System.currentTimeMillis() - startTime) + " ms");
		HtmlUtils.finishOutput(out);
	}

	/**
	 * Do a low-level healthcheck. This uses the very low-level commants to try
	 * to get Maxima to do something, and outputs lots of details along the way.
	 * @param request the request.
	 * @param response the response to send.
	 * @throws ServletException
	 * @throws IOException
	 */
	private void doHealthcheckLowLevel(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		String version = request.getParameter("version");
		ProcessConfiguration processConfig = poolCoordinator.getProcessConfiguration(version);
		if (processConfig == null) {
			throw new RuntimeException("Cannot do a low-level health-check of an unknown version.");
		}

		PrintWriter out = HtmlUtils.startOutput(response, "low-level health-check");

		HtmlUtils.writeParagraph(out, "Executing command-line: " + processConfig.commandLine);

		String currentOutput = "";

		Process process = null;
		long startTime = System.currentTimeMillis();
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.command(processConfig.commandLine.split(" "));
		processBuilder.directory(processConfig.workingDirectory);
		processBuilder.redirectErrorStream(true);
		try {
			process = processBuilder.start();
		} catch (IOException e) {
			HtmlUtils.writeException(out, e, "Exception when starting the process");
			return;
		}

		Semaphore runSwitch = new Semaphore(1);

		ReaderSucker output = new ReaderSucker(new BufferedReader(
				new InputStreamReader(new BufferedInputStream(
						process.getInputStream()))), runSwitch);
		OutputStreamWriter input = new OutputStreamWriter(new BufferedOutputStream(
				process.getOutputStream()));

		String test = processConfig.processHasStartedOutput;

		if (processConfig.extraFileToLoad == null) {
			test = processConfig.processIsReadyOutput;
		}

		currentOutput = HtmlUtils.streamOutputUntil(out, output, test, currentOutput, startTime + HEALTHCHECK_TIMEOUT);

		if (processConfig.extraFileToLoad != null) {
			String command = "load(\"" + processConfig.extraFileToLoad.getCanonicalPath().replaceAll("\\\\", "\\\\\\\\") + "\");\n";
			healthcheckSendCommand(command, input, out);
			currentOutput = HtmlUtils.streamOutputUntil(out, output, processConfig.processIsReadyOutput, currentOutput, startTime + HEALTHCHECK_TIMEOUT);
		}

		HtmlUtils.writeParagraph(out, "Start-up time: " + (System.currentTimeMillis() - startTime) + " ms");

		String killStringGen = "concat(\""
				+ processConfig.killString.substring(0, processConfig.killString.length() / 2)
				+ "\",\"" + processConfig.killString.substring(processConfig.killString.length() / 2)
				+ "\");\n";

		healthcheckSendCommand("1+1;\n" + killStringGen, input, out);
		currentOutput = HtmlUtils.streamOutputUntil(out, output, processConfig.killString, currentOutput, startTime + HEALTHCHECK_TIMEOUT);

		healthcheckSendCommand("quit();\n", input, out);
		input.close();
		output.close();

		HtmlUtils.writeParagraph(out, "Total time: " + (System.currentTimeMillis() - startTime) + " ms");
		HtmlUtils.finishOutput(out);
	}

	/**
	 * Helper method used by the low-level healthcheck.
	 * @param command The comment to send to the processes standard input.
	 * @param input The processes standard input.
	 * @param out PrintWriter for the response we are sending.
	 * @throws IOException
	 */
	private void healthcheckSendCommand(String command, OutputStreamWriter input, PrintWriter out)
			throws IOException {
		HtmlUtils.writeParagraph(out, "Sending command:");
		HtmlUtils.writeCommand(out, command);
		try {
			input.write(command);
			input.flush();
		} catch (IOException e) {
			HtmlUtils.writeException(out, e, "Exception sending the command.");
		}
	}

	/**
	 * Get various information about how the system we are running on is performing.
	 * @return Hash map containg system performance information.
	 */
	private Map<String, String> getSystemPerformance() {
		Map<String, String> values = new LinkedHashMap<String, String>();

		Runtime rt = Runtime.getRuntime();

		Calendar startTime = Calendar.getInstance();
		startTime.setTimeInMillis(servletStartTime);
		long uptime = System.currentTimeMillis() - servletStartTime;

		OperatingSystemMXBean osInfo = ManagementFactory.getOperatingSystemMXBean();

		values.put("Servlet started", StringUtils.formatTimestamp(startTime.getTime()));
		values.put("Up time", StringUtils.formatDuration(uptime));
		values.put("Active threads", "" + Thread.activeCount());
		values.put("Java free memory", StringUtils.formatBytes(rt.freeMemory())
				+ " out of " + StringUtils.formatBytes(rt.totalMemory()) + " total memory (" +
				StringUtils.formatBytes(rt.maxMemory()) + " max limit)");
		values.put("System load", osInfo.getSystemLoadAverage() + " over " + osInfo.getAvailableProcessors() + " processors");

		try {
			// Sadly, this nasty use of reflection seems to be the only way to get
			// system memory usage in Java.
			Object freeMemory = osInfo.getClass().getMethod("getFreePhysicalMemorySize").invoke(osInfo);
			Object totalMemory = osInfo.getClass().getMethod("getTotalPhysicalMemory").invoke(osInfo);
			values.put("System free memory", StringUtils.formatBytes(((Long) freeMemory).longValue())
					+ " out of " + StringUtils.formatBytes(((Long) totalMemory).longValue()));

		} catch (Exception e) {
			// Not possible to get memory usage on this system.
		}

		return values;
	}

	/**
	 * Get an optional string parameter from the request.
	 * @param request HTTP request.
	 * @param name parameter name.
	 * @param defaultValue default value to use if the property is not present.
	 * @return the requested value.
	 */
	private String getRequestString(HttpServletRequest request, String name, String defaultValue) {
		String value = request.getParameter(name);
		if (value == null) {
			return defaultValue;
		}
		return value;
	}

	/**
	 * Get an optional long parameter from the request.
	 * @param request HTTP request.
	 * @param name parameter name.
	 * @param defaultValue default value to use if the property is not present,
	 * of if it cannot be parsed as a long.
	 * @return the requested value.
	 */
	private long getRequestLong(HttpServletRequest request, String name, long defaultValue) {
		String value = request.getParameter(name);
		if (value == null) {
			return defaultValue;
		}

		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * Check whether the request contains the right admin password.
	 * @param request the request.
	 * @return wether the request contains the right password.
	 */
	private boolean checkAdminPassword(HttpServletRequest request) {
		return adminPassword.equals(request.getParameter("password"));
	}
}
