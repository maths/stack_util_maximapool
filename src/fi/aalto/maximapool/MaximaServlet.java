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
import java.io.StringWriter;
import java.io.Writer;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import fi.aalto.utils.ReaderSucker;
import fi.aalto.utils.StringUtils;



/**
 * <p>
 * A simple servlet keeping maxima processes running and executing posted
 * commands in them.
 * </p>
 *
 * @author Matti Harjula
 */
public class MaximaServlet extends HttpServlet {

	private static final long serialVersionUID = -8604075780786871066L;

	/** The configuration for the pool. */
	private MaximaProcessConfig processConfig = new MaximaProcessConfig();

	/** The configuration for the processes we create. */
	private MaximaPoolConfig poolConfig = new MaximaPoolConfig();

	/** The process pool we are using. */
	private MaximaPool maximaPool;

	/** Records when the servlet started. */
	private long servletStartTime;

	@Override
	public void init() throws ServletException {
		super.init();

		servletStartTime = System.currentTimeMillis();

		try {
			// Load properties.
			Properties properties = new Properties();
			properties.load(Thread.currentThread().getContextClassLoader()
					.getResourceAsStream("maximapool.conf"));

			File extraConfig = new File(properties.getProperty("extra.config", "false"));
			if (!"false".equals(extraConfig.getName()) && extraConfig.isFile()) {
				FileReader reader = new FileReader(extraConfig);
				properties.load(reader);
				reader.close();
			}

			processConfig.loadProperties(properties);
			poolConfig.loadProperties(properties);

		} catch (IOException e) {
			e.printStackTrace();
		}

		maximaPool = new MaximaPool(poolConfig, processConfig);
	}

	@Override
	public void destroy() {
		maximaPool.destroy();
		super.destroy();
	}

	/**
	 * Do a low-level healthcheck. This uses the very low-level commants to try
	 * to get Maxima to do something, and outputs lots of details along the way.
	 * @param request
	 * @param response
	 * @throws ServletException
	 * @throws IOException
	 */
	private void doHealthcheckLowLevel(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		PrintWriter out = healthcheckStartOutput(response);

		out.write("<p>Executing command-line: " + processConfig.cmdLine + "</p>");
		out.flush();

		String currentOutput = "";

		Process process = null;
		long startTime = System.currentTimeMillis();
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.command(processConfig.cmdLine.split(" "));
		processBuilder.directory(processConfig.cwd);
		processBuilder.redirectErrorStream(true);
		try {
			process = processBuilder.start();
		} catch (IOException e) {
			healthcheckPrintException(out, e, "Exception when starting the process");
			return;
		}

		Semaphore runSwitch = new Semaphore(1);

		ReaderSucker output = new ReaderSucker(new BufferedReader(
				new InputStreamReader(new BufferedInputStream(
						process.getInputStream()))), runSwitch);
		OutputStreamWriter input = new OutputStreamWriter(new BufferedOutputStream(
				process.getOutputStream()));

		String test = processConfig.loadReady;

		if (processConfig.load == null) {
			test = processConfig.useReady;
		}

		currentOutput = healthcheckWaitForOutput(test, output, currentOutput, out, startTime);

		if (processConfig.load != null) {
			String command = "load(\"" + processConfig.load.getCanonicalPath().replaceAll("\\\\", "\\\\\\\\") + "\");\n";
			healthcheckSendCommand(command, input, out);
			currentOutput = healthcheckWaitForOutput(processConfig.useReady, output, currentOutput, out, startTime);
		}

		out.write("<p>Start-up time: " + (System.currentTimeMillis() - startTime) + " ms</p>");

		String killStringGen = "concat(\""
				+ processConfig.killString.substring(0, processConfig.killString.length() / 2)
				+ "\",\"" + processConfig.killString.substring(processConfig.killString.length() / 2)
				+ "\");\n";

		healthcheckSendCommand("1+1;\n" + killStringGen, input, out);
		currentOutput = healthcheckWaitForOutput(processConfig.killString, output, currentOutput, out, startTime);

		healthcheckSendCommand("quit();\n", input, out);
		input.close();

		out.write("<p>Total time: " + (System.currentTimeMillis() - startTime) + " ms</p>");

		out.write("</body></html>");
	}

	/**
	 * Helper method used by the healthcheck.
	 * @param response
	 * @throws IOException
	 */
	private void healthcheckSendCommand(String command, OutputStreamWriter input, PrintWriter out)
			throws IOException {
		out.write("<p>Sending command:</p><pre class=\"command\">" + command + "</pre>");
		try {
			input.write(command);
			input.flush();
		} catch (IOException e) {
			healthcheckPrintException(out, e, "Exception sending the command.");
		}
	}

	private void healthcheckPrintException(PrintWriter out, Exception e, String message) {
		out.write("<p>" + message + "</p>");
		out.write("<pre>");
		e.printStackTrace(out);
		out.write("</pre>");
	}

	/**
	 * Helper method used by the healthcheck.
	 * @param response
	 * @throws IOException
	 */
	private String healthcheckWaitForOutput(String test,
			ReaderSucker output, String previousOutput, PrintWriter out, long startupTime)
			throws IOException {

		out.write("<p>Waiting for target text: <b>" + test + "</b></p>");
		out.flush();

		while (true) {
			try {
				Thread.sleep(0, 200);
			} catch (InterruptedException e) {
				healthcheckPrintException(out, e, "Exception while waiting for output.");
			}

			if (System.currentTimeMillis() > startupTime + processConfig.startupTime) {
				out.write("<p>Timeout!</p>");
				out.flush();
				throw new RuntimeException("Timeout");
			}

			String currentOutput = output.currentValue();

			if (!currentOutput.equals(previousOutput)) {
				out.write("<pre>" + currentOutput.substring(previousOutput.length()) + "</pre>");
				previousOutput = currentOutput;
				out.flush();
			}

			if (previousOutput.indexOf(test) >= 0) {
				break;
			}
		}

		return previousOutput;
	}

	/**
	 * This is a high-level healthcheck script which gets Maxima to do something,
	 * using the MaximaProcess class, but it run's synchonously, rather than using
	 * the pool.
	 * @param request
	 * @param response
	 * @throws ServletException
	 * @throws IOException
	 */
	private void doHealthcheckHighLevel(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		Writer out = healthcheckStartOutput(response);

		long startTime = System.currentTimeMillis();
		MaximaProcess mp = maximaPool.makeProcess();
		String firstOutput = mp.getOutput();
		out.write("<pre>" + firstOutput + "</pre>");
		out.flush();

		out.write("<p>Sending command: <b>1+1;</b>.</p>");
		out.flush();

		mp.doAndDie("1+1;\n", 10000);
		String secondOutput = mp.getOutput();
		out.write("<pre>" + secondOutput.substring(firstOutput.length()) + "</pre>");
		out.flush();

		out.write("<p>Time taken: " + (System.currentTimeMillis() - startTime) + " ms</p>");
		out.write("</body></html>");
	}

	/**
	 * Helper method used by the healthcheck.
	 * @param response
	 * @throws IOException
	 */
	private PrintWriter healthcheckStartOutput(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html");

		PrintWriter out = response.getWriter();

		out.write("<html><head>" +
				"<title>MaximaPool - health-check</title>" +
				"<style type=\"text/css\">" +
					"pre { padding: 0.5em; background: #eee; }" +
					"pre.command { background: #dfd; }" +
				"</style>" +
				"</head><body>");

		out.write("<p>Trying to start a Maxima process.</p>");
		out.flush();

		return out;
	}

	private Map<String, String> getSystemPerformance() {
		Map<String, String> values = new LinkedHashMap<String, String>();

		Runtime rt = Runtime.getRuntime();

		Calendar startTime = Calendar.getInstance();
		startTime.setTimeInMillis(servletStartTime);
		long uptime = System.currentTimeMillis() - servletStartTime;

		values.put("Servlet started", StringUtils.formatTimestamp(startTime.getTime()));
		values.put("Up time", StringUtils.formatDuration(uptime));
		values.put("Free memory", StringUtils.formatBytes(rt.freeMemory())
				+ " out of " + StringUtils.formatBytes(rt.totalMemory()) + " total memory (" +
				StringUtils.formatBytes(rt.maxMemory()) + " max limit).");
		values.put("Active threads", "" + Thread.activeCount());

		return values;
	}

	/**
	 * Display the current status of the servlet, with a form that can be used
	 * for testing.
	 * @param request
	 * @param response
	 * @throws ServletException
	 * @throws IOException
	 */
	private void doStatus(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html");

		Writer out = response.getWriter();

		out.write("<html><head><title>MaximaPool - status display</title></head><body>");

		out.write("<h3>Current pool performance</h3>");
		outputMapAsTable(out, maximaPool.getStatus());

		out.write("<h3>Current system performance</h3>");
		outputMapAsTable(out, getSystemPerformance());

		out.write("<h3>Health-check</h3>");
		out.write("<p><A href=\"?healthcheck=1\">Run the low-level health-check</a></p>");
		out.write("<p><A href=\"?healthcheck=2\">Run the high-level health-check</a></p>");

		out.write("<h3>Test form</h3>");
		out.write("<p>Input something for evaluation</p>");
		out.write("<form method='post'><textarea name='input'></textarea><br/>Timeout (ms): <select name='timeout'><option value='1000'>1000</option><option value='2000'>2000</option><option value='3000' selected='selected'>3000</option><option value='4000'>4000</option><option value='5000'>5000</option></select><br/><input type='submit' value='Eval'/></form>");

		out.write("<h3>Maxima pool configuration</h3>");
		outputMapAsTable(out, poolConfig.describe());

		out.write("<h3>Maxima process configuration</h3>");
		outputMapAsTable(out, processConfig.describe());

		out.write("</body></html>");
	}

	private void outputMapAsTable(Writer out, Map<String, String> values) throws IOException {
		out.write("<table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody>");
		for (Map.Entry<String, String> entry : values.entrySet()) {
			out.write("<tr><td>" + entry.getKey() + ":</td><td>" +
					entry.getValue() + "</td></tr>");
		}
		out.write("</tbody></table>");
	}

	@Override
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		try {
			// Dispatch the request.
			if ("healthcheck=1".equals(request.getQueryString())) {
				doHealthcheckLowLevel(request, response);

			} else if ("healthcheck=2".equals(request.getQueryString())) {
				doHealthcheckHighLevel(request, response);

			} else {
				doStatus(request, response);
			}

		} catch (Exception e) {
			// Display any exceptions.
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"<p>" + e.getMessage() + "</p><pre>" + sw.toString() + "</pre>");
		}
	}

	@Override
	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		String theInput = request.getParameter("input");

		// NOTE! the obvious lack of input sanity checks... so think where you
		// use this.
		MaximaProcess mp = maximaPool.getProcess();

		long limit = 3000;
		if (request.getParameter("timeout") != null)
			try {
				limit = Long.parseLong(request.getParameter("timeout"));
			} catch (NumberFormatException e) {
				e.printStackTrace();
			}

		if (mp.doAndDie(theInput, limit)) {
			response.setStatus(HttpServletResponse.SC_OK);
		} else {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
		}

		String out = mp.getOutput();

		if (mp.filesGenerated().size() > 0) {
			response.setContentType("application/zip");
			ZipOutputStream zos = new ZipOutputStream(response.getOutputStream());

			ZipEntry z = new ZipEntry("OUTPUT");
			zos.putNextEntry(z);
			zos.write(out.getBytes());
			zos.closeEntry();
			mp.addGeneratedFilesToZip(zos);
			zos.finish();

		} else {
			response.setContentType("text/plain");
			response.getWriter().write(out);
		}

		maximaPool.notifyProcessFinishedWith(mp);
	}
}
