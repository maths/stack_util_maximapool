package fi.aalto.maximapool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
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
public class MaximaPool extends HttpServlet {

	private static final long serialVersionUID = -8604075780786871066L;

	private ProcessBuilder processBuilder = new ProcessBuilder();

	private long updateCycle = 500;
	private long startupTimeEstimate = 2000;
	private double demandEstimate = 0.001;
	private int averageCount = 5;
	private double safetyMultiplier = 3.0;

	// These should probably be volatile, but then you would need to make sure
	// that the processes die though some other means.

	// The pool for ready processes
	private BlockingDeque<MaximaProcess> pool = new LinkedBlockingDeque<MaximaProcess>();

	// The pool of processes in use
	private List<MaximaProcess> usedPool = Collections
			.synchronizedList(new LinkedList<MaximaProcess>());

	private Properties properties = new Properties();

	private int poolMin = 5;
	private int poolMax = 100;
	private int startupLimit = 20;

	private boolean fileHandling = false;
	private String pathCommandTemplate = "TMP_IMG_DIR: \"%WORK-DIR%\"; IMG_DIR: \"%OUTPUT-DIR%\";";

	private String killString = "--COMPLETED--kill--PROCESS--";
	private String cmdLine = "maxima";
	private File cwd = new File(".");
	private File load = null;
	private String loadReady = "(%i1)";
	private String useReady = "(%i1)";

	private long executionTime = 30000;
	private long lifeTime = 60000000;

	private List<Long> startupTimeHistory = Collections
			.synchronizedList(new LinkedList<Long>());
	private List<Long> requestTimeHistory = Collections
			.synchronizedList(new LinkedList<Long>());

	private volatile Semaphore startupThrotle;

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public MaximaPool() {
		super();

		try {
			// load properties
			properties.load(Thread.currentThread().getContextClassLoader()
					.getResourceAsStream("maximapool.conf"));

			updateCycle = Long.parseLong(properties.getProperty(
					"pool.update.cycle", "500"));
			startupTimeEstimate = Long.parseLong(properties.getProperty(
					"pool.adaptation.startuptime.initial.estimate", "2000"));
			demandEstimate = Double.parseDouble(properties.getProperty(
					"pool.adaptation.demand.initial.estimate", "0.001"));
			averageCount = Integer.parseInt(properties.getProperty(
					"pool.adaptation.averages.length", "5"));
			safetyMultiplier = Double.parseDouble(properties.getProperty(
					"pool.adaptation.safety.multiplier", "3.0"));
			poolMin = Integer.parseInt(properties.getProperty("pool.size.min",
					"5"));
			poolMax = Integer.parseInt(properties.getProperty("pool.size.max",
					"100"));
			startupLimit = Integer.parseInt(properties.getProperty(
					"pool.start.limit", "20"));

			cmdLine = properties.getProperty("maxima.commandline", "maxima");
			loadReady = properties
					.getProperty("maxima.ready.for.load", "(%i1)");
			useReady = properties.getProperty("maxima.ready.for.use", "(%i1)");
			cwd = new File(properties.getProperty("maxima.cwd", "."));
			load = new File(properties.getProperty("maxima.load", "false"));
			if (load.getName().equals("false"))
				load = null;

			fileHandling = properties.getProperty("file.handling", "false")
					.equalsIgnoreCase("true");
			pathCommandTemplate = properties.getProperty("maxima.path.command",
					"TMP_IMG_DIR: \"%WORK-DIR%\"; IMG_DIR: \"%OUTPUT-DIR%\";");

			executionTime = Long.parseLong(properties.getProperty(
					"pool.execution.time.limit", "30000"));
			lifeTime = Long.parseLong(properties.getProperty(
					"pool.process.lifetime", "60000000"));

			// init the datasets
			startupTimeHistory.add(startupTimeEstimate);
			requestTimeHistory.add(System.currentTimeMillis());

			// setup the processBuilder
			processBuilder.command(cmdLine);
			processBuilder.directory(cwd);
			processBuilder.redirectErrorStream(true);
		} catch (IOException e) {
			System.out.println("Load error: did you lose maximapool.conf?");
			e.printStackTrace();
		}

		this.startupThrotle = new Semaphore(startupLimit);

		/**
		 * Lets try to kill the processes when the server shuts down...
		 */
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				for (fi.aalto.maximapool.MaximaPool.MaximaProcess mp : pool)
					mp.kill();
				pool.clear();
			}
		});

		Thread upKeep = new Thread() {
			public void run() {
				long sleep = updateCycle;
				while (true) {
					long testTime = System.currentTimeMillis();

					// Kill off old ones
					MaximaProcess mp = null;
					try {
						mp = pool.take();
					} catch (InterruptedException e1) {
						mp = null;
					}
					while (mp != null && mp.liveTill < testTime) {
						mp.kill();
						try {
							mp = pool.take();
						} catch (InterruptedException e) {
							mp = null;
						}
					}
					if (mp != null)
						pool.addFirst(mp);

					while (usedPool.size() > 0
							&& usedPool.get(0).liveTill < testTime) {
						mp = usedPool.remove(0);
						try {
							mp.process.exitValue();
						} catch (Exception e) {
							mp.kill();
						}
					}

					// Prune datasets
					while (startupTimeHistory.size() > averageCount)
						startupTimeHistory.remove(0);
					while (requestTimeHistory.size() > averageCount)
						requestTimeHistory.remove(0);

					// Do estimates
					startupTimeEstimate = 0;
					for (long t : startupTimeHistory)
						startupTimeEstimate += t;
					startupTimeEstimate /= startupTimeHistory.size();

					// +1 just to make sure that a startup moment exception can
					// be skipped
					demandEstimate = requestTimeHistory.size()
							/ ((System.currentTimeMillis() - requestTimeHistory
									.get(0)) + 1.0);

					// Guestimate demand for N
					double N = demandEstimate * safetyMultiplier * sleep;

					if (N < poolMin)
						N = poolMin;
					if (N > poolMax)
						N = poolMax;

					// Startup new ones if need be
					double currentN = pool.size() + startupLimit
							- startupThrotle.availablePermits();
					while (currentN < N
							&& startupThrotle.availablePermits() > 0) {
						N -= 1.0;
						Starter starter = new Starter();
						starter.start();
					}

					try {
						Thread.sleep(sleep);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		};
		upKeep.setDaemon(true);
		upKeep.setName("MaximaPool-upkeep");
		upKeep.start();
	}

	private static long startCount = 0;

	private class Starter extends Thread {

		public Starter() {
			super();
			startCount++;
			this.setName(Thread.currentThread().getName() + "-starter-"
					+ startCount);
		}

		public void run() {
			startupThrotle.acquireUninterruptibly();
			MaximaProcess mp = new MaximaProcess();
			startupTimeHistory.add(mp.startupTime);
			pool.add(mp);
			startupThrotle.release();
		}
	}

	public void doHealthcheckLowLevel(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		Writer out = healthcheckStartOutput(response);

		out.write("<p>Executing command-line: " + cmdLine + "</p>");
		out.flush();

		String currentOutput = "";

		Process process = null;
		long startupTime = System.currentTimeMillis();
		try {
			process = processBuilder.start();
		} catch (IOException e) {
			System.out.println("Process startup failure...");
			e.printStackTrace();
			return;
		}

		Semaphore runSwitch = new Semaphore(1);

		InputStreamReaderSucker output = new InputStreamReaderSucker(new BufferedReader(
				new InputStreamReader(new BufferedInputStream(
						process.getInputStream()))), runSwitch);
		OutputStreamWriter input = new OutputStreamWriter(new BufferedOutputStream(
				process.getOutputStream()));

		String test = loadReady;

		if (load == null) {
			test = useReady;
		}

		currentOutput = healthcheckWaitForOutput(test, output, currentOutput, out, startupTime);

		String command = "load(\"" + load.getCanonicalPath().replaceAll("\\\\", "\\\\\\\\") + "\");\n";
		if (load != null) {
			healthcheckSendCommand(command, input, out);
			currentOutput = healthcheckWaitForOutput(useReady, output, currentOutput, out, startupTime);
		}

		out.write("<p>startupTime = " + (System.currentTimeMillis() - startupTime) + "</p>");

		String killStringGen = "concat(\""
				+ killString.substring(0, killString.length() / 2)
				+ "\",\"" + killString.substring(killString.length() / 2)
				+ "\");\n";

		healthcheckSendCommand("1+1;\n" + killStringGen, input, out);
		currentOutput = healthcheckWaitForOutput(killString, output, currentOutput, out, startupTime);

		healthcheckSendCommand("quit();\n", input, out);
		input.close();

		out.write("</body></html>");
	}

	protected void healthcheckSendCommand(String command, OutputStreamWriter input, Writer out)
			throws IOException {
		out.write("<p>Sending command:</p><pre class=\"command\">" + command + "</pre>");
		try {
			input.write(command);
			input.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private String healthcheckWaitForOutput(String test,
			InputStreamReaderSucker output, String previousOutput, Writer out, long startupTime)
			throws IOException {

		out.write("<p>Waiting for target text: <b>" + test + "</b></p>");
		out.flush();

		while (true) {
			try {
				Thread.sleep(0, 200);
			} catch (InterruptedException e) {
				e.printStackTrace();
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

	public void doHealthcheckHighLevel(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		Writer out = healthcheckStartOutput(response);

		MaximaProcess mp = new MaximaProcess();
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

	protected Writer healthcheckStartOutput(HttpServletResponse response) throws IOException {
		response.setStatus(200);
		response.setContentType("text/html");

		Writer out = response.getWriter();

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
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		if ("healthcheck=1".equals(request.getQueryString())) {
			doHealthcheckLowLevel(request, response);
			return;
		}

		if ("healthcheck=2".equals(request.getQueryString())) {
			doHealthcheckHighLevel(request, response);
			return;
		}

		response.setStatus(200);
		response.setContentType("text/html");

		Writer out = response.getWriter();

		out.write("<html><head><title>MaximaPool - status display</title></head><body>");

		out.write("<h3>Health-check</h3>");
		out.write("<p><A href=\"?healthcheck=1\">Run the low-level health-check</a></p>");
		out.write("<p><A href=\"?healthcheck=2\">Run the high-level health-check</a></p>");

		out.write("<h3>Numbers</h3>");
		out.write("<table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody>");

		out.write("<tr><td>Ready processes in the pool:</td><td>" + pool.size()
				+ "</td></tr>");
		out.write("<tr><td>Processes in use:</td><td>" + usedPool.size()
				+ "</td></tr>");
		out.write("<tr><td>Current demand estimate:</td><td>" + demandEstimate
				* 1000.0 + " Hz</td></tr>");
		out.write("<tr><td>Current startuptime:</td><td>" + startupTimeEstimate
				+ " ms</td></tr>");

		out.write("</tbody></table>");

		out.write("<h3>Test form</h3>");
		out.write("<p>Input something for evaluation</p>");
		out.write("<form method='POST'><textarea name='input'></textarea><br/>Timeout (ms): <select name='timeout'><option value='1000'>1000</option><option value='2000'>2000</option><option value='3000' selected='selected'>3000</option><option value='4000'>4000</option><option value='5000'>5000</option></select><br/><input type='submit' value='Eval'/></form>");

		out.write("</body></html>");
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		String theInput = request.getParameter("input");
		requestTimeHistory.add(System.currentTimeMillis());

		// NOTE! the obvious lack of input sanity checks... so think where you
		// use this.
		MaximaProcess mp = getProcess();

		long limit = 3000;
		if (request.getParameter("timeout") != null)
			try {
				limit = Long.parseLong(request.getParameter("timeout"));
			} catch (NumberFormatException e) {
				e.printStackTrace();
			}

		if (mp.doAndDie(theInput, limit))
			response.setStatus(200);
		else
			response.setStatus(416);

		String out = mp.output.currentValue();
		if (out.indexOf("\"" + killString) > 0)
			out = out.substring(0, out.indexOf("\"" + killString));
		else if (out.indexOf(killString) > 0)
			out = out.substring(0, out.indexOf(killString));

		usedPool.remove(mp);

		if (fileHandling && mp.filesGenerated().size() > 0) {
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

	private MaximaProcess getProcess() {
		// Start a new one as we are going to take one...
		if (startupThrotle.availablePermits() > 0) {
			Starter starter = new Starter();
			starter.start();
		}

		MaximaProcess mp = null;
		while (mp == null) {
			try {
				mp = pool.take();
			} catch (InterruptedException e) {
				e.printStackTrace();
				try {
					Thread.sleep(3);
				} catch (InterruptedException ee) {
					ee.printStackTrace();
				}
			}
		}
		mp.liveTill += executionTime;
		usedPool.add(mp);
		mp.activate();

		return mp;
	}

	class MaximaProcess {
		public static final long STARTUP_TIMEOUT = 10000; // miliseconds

		Process process = null;

		File baseDir = null;

		boolean ready = false;
		long startupTime = -1;

		long liveTill = System.currentTimeMillis() + lifeTime;

		OutputStreamWriter input = null;
		InputStreamReaderSucker output = null;

		Semaphore runSwitch = new Semaphore(1);

		/**
		 * This constructor blocks till it is ready so create in a thread...
		 */
		MaximaProcess() {
			startupTime = System.currentTimeMillis();
			try {
				process = processBuilder.start();
			} catch (IOException e) {
				System.out.println("Process startup failure...");
				e.printStackTrace();
				return;
			}

			output = new InputStreamReaderSucker(new BufferedReader(
					new InputStreamReader(new BufferedInputStream(process
							.getInputStream()))), runSwitch);
			input = new OutputStreamWriter(new BufferedOutputStream(process
					.getOutputStream()));

			String test = loadReady;

			if (load == null)
				test = useReady;

			while (output.currentValue().indexOf(test) < 0) {
				try {
					Thread.sleep(0, 200);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				if (System.currentTimeMillis() > startupTime + STARTUP_TIMEOUT) {
					throw new RuntimeException("Process start timeout");
				}
			}
			if (load == null) {
				ready = true;
				if (fileHandling)
					setupFiles();
				startupTime = System.currentTimeMillis() - startupTime;
				return;
			}

			try {
				String command = "load(\""
						+ load.getCanonicalPath()
								.replaceAll("\\\\", "\\\\\\\\") + "\");\n";
				input.write(command);

				input.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}

			test = useReady;

			while (output.currentValue().indexOf(test) < 0) {
				try {
					Thread.sleep(0, 200);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				if (System.currentTimeMillis() > startupTime + STARTUP_TIMEOUT) {
					throw new RuntimeException("Process start timeout");
				}
			}

			ready = true;
			if (fileHandling)
				setupFiles();
			startupTime = System.currentTimeMillis() - startupTime;

		}

		private void setupFiles() {
			try {
				baseDir = File.createTempFile("mp-", "-" + process.hashCode());
				baseDir.delete();
				baseDir.mkdirs();
				File output = new File(baseDir, "output");
				File work = new File(baseDir, "work");
				output.mkdir();
				work.mkdir();

				String command = pathCommandTemplate;
				command = command.replaceAll("%OUTPUT-DIR-NE%", output
						.getCanonicalPath());
				command = command.replaceAll("%WORK-DIR-NE%", work
						.getCanonicalPath());
				command = command.replaceAll("%OUTPUT-DIR%", output
						.getCanonicalPath().replaceAll("\\\\", "\\\\\\\\"));
				command = command.replaceAll("%WORK-DIR%", work
						.getCanonicalPath().replaceAll("\\\\", "\\\\\\\\"));
				input.write(command);
				input.flush();
			} catch (IOException e) {
				System.out
						.println("File handling failure, maybe the securitymanager has something against us?");
				e.printStackTrace();
			}
		}

		void activate() {
			runSwitch.release(1);
		}

		void deactivate() {
			try {
				runSwitch.acquire();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		/**
		 * Returns true if we did not timeout...
		 * 
		 * @param command
		 * @param timeout
		 *            limit in ms
		 * @return
		 */
		boolean doAndDie(String command, long timeout) {
			String killStringGen = "concat(\""
					+ killString.substring(0, killString.length() / 2)
					+ "\",\"" + killString.substring(killString.length() / 2)
					+ "\");";

			try {
				input.write(command + killStringGen + "quit();\n");
				input.close();
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}

			// Basic limit for catching hanged or too long runs
			long limit = timeout + System.currentTimeMillis();

			// Give it some time before checking for closure
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			boolean processDone = false;
			boolean readDone = false;
			while (!readDone) {
				if (!processDone)
					try {
						process.exitValue();
						processDone = true;
					} catch (Exception ee) {

					}
				if (output.currentValue().contains(killString)) {
					output.close();
					kill();
					return true;
				}

				if (processDone)
					readDone = output.foundEnd;

				if (limit < System.currentTimeMillis()) {
					output.close();
					kill();
					return false;
				}

				if (readDone) {
					output.close();
					return true;
				}
				// Read not done Not wait some more
				if (!readDone)
					try {
						Thread.sleep(0, 100);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
			}
			output.close();
			deactivate();
			return true;
		}

		void kill() {
			runSwitch.release();
			output.close();
			try {
				process.exitValue();
				return;
			} catch (Exception e) {
				// Nope just testing if it was already down
			}
			try {
				input.write("quit();\n\n");
				input.close();
			} catch (IOException e1) {
			}

			output.close();
			try {
				process.exitValue();
			} catch (Exception e) {
				process.destroy();
			}
		}

		List<File> filesGenerated() {
			return listFilesInOrder(new File(baseDir, "output"), false);
		}

		@Override
		protected void finalize() throws Throwable {
			kill();
			if (fileHandling) {
				for (File f : listFilesInOrder(baseDir, true)) {
					f.delete();
				}
				baseDir.delete();
			}

			super.finalize();
		}
	}

	/**
	 * an utility for fast reading of STDOUT & STDERR...
	 * 
	 * @author Matti Harjula
	 * 
	 */
	class InputStreamReaderSucker {

		StringBuffer value = new StringBuffer();

		Reader reader = null;

		volatile boolean foundEnd = false;

		Thread worker = null;

		Semaphore runSwitch;

		InputStreamReaderSucker(Reader source, Semaphore runSwitch) {
			reader = source;
			this.runSwitch = runSwitch;
			start();
		}

		public void start() {
			worker = new Thread() {
				public void run() {
					char[] buffy = new char[1024];
					int i = 0;
					while (!foundEnd) {
						try {
							runSwitch.acquire();
						} catch (InterruptedException e1) {
							// TODO Auto-generated catch block
							e1.printStackTrace();
						}
						i = 0;
						try {
							if (reader.ready())
								i = reader.read(buffy);
						} catch (IOException e) {
						}
						runSwitch.release();
						if (i > 0)
							value.append(new String(buffy, 0, i));
						else if (i == -1) {
							foundEnd = true;
							break;
						}

						// Sleep here so the stream does not get too much
						// attention
						try {
							Thread.sleep(0, 100);
						} catch (InterruptedException e) {
						}
					}
					try {
						if (foundEnd)
							reader.close();
					} catch (IOException e) {
					}
				}

			};
			worker.setName(Thread.currentThread().getName() + "-reader");
			worker.start();
		}

		void close() {
			foundEnd = true;
			try {
				reader.close();
			} catch (IOException e) {
			}
		}

		String currentValue() {
			return value.toString();
		}
	}

	/**
	 * Lists all the files under this dir if sub dirs are listed they are listed
	 * in such an order that you may delete all the files in the order of the
	 * list.
	 * 
	 * @param baseDir
	 * @param listDirs
	 * @return
	 */
	public static List<File> listFilesInOrder(File baseDir, boolean listDirs) {
		List<File> R = new LinkedList<File>();

		for (File f : baseDir.listFiles())
			if (f.isDirectory()) {
				R.addAll(listFilesInOrder(f, listDirs));
				if (listDirs)
					R.add(f);
			} else
				R.add(f);

		return R;
	}

}
