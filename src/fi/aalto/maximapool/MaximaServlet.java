package fi.aalto.maximapool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


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

	private static final long MINUTE = 60*1000;
	private static final long HOUR = 60*MINUTE;
	private static final long DAY = 24*HOUR;

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
			maximaPool = new MaximaPool(properties);

		} catch (IOException e) {
			System.out.println("Load error: did you lose maximapool.conf?");
			e.printStackTrace();
		}

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
	public void doHealthcheckLowLevel(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		PrintWriter out = healthcheckStartOutput(response);

		out.write("<p>Executing command-line: " + maximaPool.config.cmdLine + "</p>");
		out.flush();

		String currentOutput = "";

		Process process = null;
		long startupTime = System.currentTimeMillis();
		try {
			process = maximaPool.processBuilder.start();
		} catch (IOException e) {
			healthcheckPrintException(out, e, "Exception when starting the process");
			return;
		}

		Semaphore runSwitch = new Semaphore(1);

		InputStreamReaderSucker output = new InputStreamReaderSucker(new BufferedReader(
				new InputStreamReader(new BufferedInputStream(
						process.getInputStream()))), runSwitch);
		OutputStreamWriter input = new OutputStreamWriter(new BufferedOutputStream(
				process.getOutputStream()));

		String test = maximaPool.config.loadReady;

		if (maximaPool.config.load == null) {
			test = maximaPool.config.useReady;
		}

		currentOutput = healthcheckWaitForOutput(test, output, currentOutput, out, startupTime);

		if (maximaPool.config.load != null) {
			String command = "load(\"" + maximaPool.config.load.getCanonicalPath().replaceAll("\\\\", "\\\\\\\\") + "\");\n";
			healthcheckSendCommand(command, input, out);
			currentOutput = healthcheckWaitForOutput(maximaPool.config.useReady, output, currentOutput, out, startupTime);
		}

		out.write("<p>startupTime = " + (System.currentTimeMillis() - startupTime) + "</p>");

		String killStringGen = "concat(\""
				+ maximaPool.config.killString.substring(0, maximaPool.config.killString.length() / 2)
				+ "\",\"" + maximaPool.config.killString.substring(maximaPool.config.killString.length() / 2)
				+ "\");\n";

		healthcheckSendCommand("1+1;\n" + killStringGen, input, out);
		currentOutput = healthcheckWaitForOutput(maximaPool.config.killString, output, currentOutput, out, startupTime);

		healthcheckSendCommand("quit();\n", input, out);
		input.close();

		out.write("</body></html>");
	}

	/**
	 * Helper method used by the healthcheck.
	 * @param response
	 * @throws IOException
	 */
	protected void healthcheckSendCommand(String command, OutputStreamWriter input, PrintWriter out)
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
			InputStreamReaderSucker output, String previousOutput, PrintWriter out, long startupTime)
			throws IOException {

		out.write("<p>Waiting for target text: <b>" + test + "</b></p>");
		out.flush();

		while (true) {
			try {
				Thread.sleep(0, 200);
			} catch (InterruptedException e) {
				healthcheckPrintException(out, e, "Exception while waiting for output.");
			}

			if (System.currentTimeMillis() > startupTime + 10000) {
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
	public void doHealthcheckHighLevel(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		Writer out = healthcheckStartOutput(response);

		MaximaProcess mp = new MaximaProcess(maximaPool.processBuilder, maximaPool.config);
		String firstOutput = mp.output.currentValue();
		out.write("<pre>" + firstOutput + "</pre>");
		out.flush();

		out.write("<p>Sending command: <b>1+1;</b>.</p>");
		out.flush();

		mp.doAndDie("1+1;\n", 10000);
		String secondOutput = mp.output.currentValue();
		out.write("<pre>" + secondOutput.substring(firstOutput.length()) + "</pre>");
		out.flush();

		out.write("</body></html>");
	}

	/**
	 * Helper method used by the healthcheck.
	 * @param response
	 * @throws IOException
	 */
	protected PrintWriter healthcheckStartOutput(HttpServletResponse response) throws IOException {
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

	/**
	 * Display the current status of the servlet, with a form that can be used
	 * for testing.
	 * @param request
	 * @param response
	 * @throws ServletException
	 * @throws IOException
	 */
	protected void doStatus(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		Runtime rt = Runtime.getRuntime();

		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(servletStartTime);
		long uptime = System.currentTimeMillis() - servletStartTime;
		String upSinceTime = (new SimpleDateFormat("HH:mm")).format(c.getTime());
		String upSinceDate = (new SimpleDateFormat("yyyy-MM-dd")).format(c.getTime());

		long uptimeDays = uptime / DAY;
		long uptimeHours = (uptime - uptimeDays * DAY) / HOUR;
		c.set(Calendar.HOUR_OF_DAY, (int)uptimeHours);
		long uptimeMinutes = (uptime - uptimeDays * DAY - uptimeHours * HOUR) / MINUTE;
		c.set(Calendar.MINUTE, (int)uptimeMinutes);

		Map<String, String> status = maximaPool.getStatus();

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html");

		Writer out = response.getWriter();

		out.write("<html><head><title>MaximaPool - status display</title></head><body>");

		out.write("<h3>Current performance</h3>");
		out.write("<table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody>");
		out.write("<tr><td>Servlet started:</td><td>" + upSinceTime + " " + upSinceDate
				+ " (" + uptimeDays + " days, " + uptimeHours + " hours, " + uptimeMinutes
				+ " minutes ago)</td></tr>");
		out.write("<tr><td>Free memory:</td><td>" + StringUtils.formatBytes(rt.freeMemory())
				+ " out of " + StringUtils.formatBytes(rt.totalMemory()) + " total memory (" +
				StringUtils.formatBytes(rt.maxMemory()) + " max limit)."
				+ "</td></tr>");
		for (Map.Entry<String, String> entry : status.entrySet()) {
			out.write("<tr><td>" + entry.getKey() + ":</td><td>" +
					entry.getValue() + "</td></tr>");
			if ("Active threads".equals(entry.getKey())) {
				break;
			}
		}
		out.write("</tbody></table>");

		out.write("<h3>Health-check</h3>");
		out.write("<p><A href=\"?healthcheck=1\">Run the low-level health-check</a></p>");
		out.write("<p><A href=\"?healthcheck=2\">Run the high-level health-check</a></p>");

		out.write("<h3>Test form</h3>");
		out.write("<p>Input something for evaluation</p>");
		out.write("<form method='POST'><textarea name='input'></textarea><br/>Timeout (ms): <select name='timeout'><option value='1000'>1000</option><option value='2000'>2000</option><option value='3000' selected='selected'>3000</option><option value='4000'>4000</option><option value='5000'>5000</option></select><br/><input type='submit' value='Eval'/></form>");

		out.write("<h3>Configuration</h3>");
		out.write("<table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody>");
		boolean output = false;
		for (Map.Entry<String, String> entry : status.entrySet()) {
			if (output) {
				out.write("<tr><td>" + entry.getKey() + ":</td><td>" +
						entry.getValue() + "</td></tr>");
			}
			if ("Active threads".equals(entry.getKey())) {
				output = true;
			}
		}
		out.write("</tbody></table>");

		out.write("</body></html>");
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
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

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
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
			ZipOutputStream zos = new ZipOutputStream(response
					.getOutputStream());

			byte[] buffy = new byte[4096];
			int c = -1;
			ZipEntry z = new ZipEntry("OUTPUT");
			zos.putNextEntry(z);
			zos.write(out.getBytes());
			zos.closeEntry();
			for (File f : mp.filesGenerated()) {
				String name = f.getCanonicalPath().replace(
						new File(mp.baseDir, "output").getCanonicalPath(), "");
				z = new ZipEntry(name);
				zos.putNextEntry(z);
				FileInputStream fis = new FileInputStream(f);
				c = fis.read(buffy);
				while (c > 0) {
					zos.write(buffy, 0, c);
					c = fis.read(buffy);
				}
				fis.close();
				zos.closeEntry();
			}
			zos.finish();
		} else {
			response.setContentType("text/plain");
			response.getWriter().write(out);
		}
	}
}
