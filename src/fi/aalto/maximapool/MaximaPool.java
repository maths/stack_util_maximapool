package fi.aalto.maximapool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;

/**
 * <p>
 * A simple servlet keeping maxima processes running and executing posted
 * commands in them.
 * </p>
 * 
 * @author Matti Harjula
 */
public class MaximaPool {

	ProcessBuilder processBuilder = new ProcessBuilder();

	private long updateCycle = 500;
	private long startupTimeEstimate = 2000;
	private double demandEstimate = 0.001;
	private int averageCount = 5;
	private double safetyMultiplier = 3.0;

	// These should probably be volatile, but then you would need to make sure
	// that the processes die though some other means.

	// The pool for ready processes
	BlockingDeque<MaximaProcess> pool = new LinkedBlockingDeque<MaximaProcess>();

	// The pool of processes in use
	private List<MaximaProcess> usedPool = Collections
			.synchronizedList(new LinkedList<MaximaProcess>());

	private int poolMin = 5;
	private int poolMax = 100;
	private int startupLimit = 20;

	private boolean fileHandling = false;
	private String pathCommandTemplate = "TMP_IMG_DIR: \"%WORK-DIR%\"; IMG_DIR: \"%OUTPUT-DIR%\";";

	String killString = "--COMPLETED--kill--PROCESS--";
	String cmdLine = "maxima";
	private File cwd = new File(".");
	File load = null;
	String loadReady = "(%i1)";
	String useReady = "(%i1)";

	private long executionTime = 30000;
	private long lifeTime = 60000000;

	List<Long> startupTimeHistory = Collections
			.synchronizedList(new LinkedList<Long>());
	private List<Long> requestTimeHistory = Collections
			.synchronizedList(new LinkedList<Long>());

	volatile Semaphore startupThrotle;

	private UpkeepThread upKeep;

	MaximaPool(Properties properties) {
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

		// Initialise the datasets.
		startupTimeHistory.add(startupTimeEstimate);
		requestTimeHistory.add(System.currentTimeMillis());

		// Set up the processBuilder
		processBuilder.command(cmdLine.split(" "));
		processBuilder.directory(cwd);
		processBuilder.redirectErrorStream(true);

		// Create the startup throttle.
		this.startupThrotle = new Semaphore(startupLimit);

		// Start the upkeep thread.
		upKeep = new UpkeepThread(this, updateCycle);
		upKeep.start();
	}

	/**
	 * Tells the pool that a process is being started.
	 * This method will not return until a startupThrotle semaphore has been
	 * acquired.
	 */
	void notifyStartingProcess() {
		startupThrotle.acquireUninterruptibly();
	}

	/**
	 * Tells the pool that a process has started up, and is ready for use.
	 * @param mp the newly started process.
	 */
	public void notifyProcessReady(MaximaProcess mp) {
		startupTimeHistory.add(mp.startupTime);
		pool.add(mp);
		startupThrotle.release();
	}

	/**
	 * @return Map<String, String> a hash map containing lots of data about
	 * the current state of the pool.
	 */
	protected Map<String, String> getStatus() {

		Map<String, String> status = new LinkedHashMap<String, String>();

		status.put("Ready processes in the pool", "" + pool.size());
		status.put("Processes in use", "" + usedPool.size());
		status.put("Current demand estimate", demandEstimate * 1000.0 + " Hz");
		status.put("Current startuptime", startupTimeEstimate + " ms");
		status.put("Active threads", "" + Thread.activeCount());
		status.put("Maxima command-line", cmdLine);
		if (load != null) {
			try {
				status.put("File to load", load.getCanonicalPath());
			} catch (IOException e) {
			}
		}
		status.put("Started test string", loadReady);
		status.put("Loaded test string", useReady);
		status.put("File handling", fileHandling ? "On" : "Off");
		status.put("File paths template", pathCommandTemplate);
		status.put("Min pool size", "" + poolMin);
		status.put("Max pool size", "" + poolMax);
		status.put("Pool update cycle time", updateCycle + " ms");
		status.put("Number of data points for averages", "" + averageCount);
		status.put("Pool size safety multiplier", "" + safetyMultiplier);
		status.put("Execution extra time limit", executionTime + " ms");
		status.put("Process life time limit", lifeTime + " ms");

		return status;
	}

	/**
	 * Get a MaximaProcess from the pool.
	 */
	MaximaProcess getProcess() {
		requestTimeHistory.add(System.currentTimeMillis());

		// Start a new one as we are going to take one...
		if (startupThrotle.availablePermits() > 0) {
			ProcessStarter starter = new ProcessStarter(this);
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

	void notifyProcessFinishedWith(MaximaProcess mp) {
		usedPool.remove(mp);
	}

	void killOverdueProcesses() {
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
	}

	double updateEstimates(long sleep) {
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
		return N;
	}

	void startProcesses(double numProcessesRequired) {
		double numProcesses = pool.size() +
				startupLimit - startupThrotle.availablePermits();

		while (numProcesses < numProcessesRequired
				&& startupThrotle.availablePermits() > 0) {
			numProcesses += 1.0;
			ProcessStarter starter = new ProcessStarter(this);
			starter.start();
		}
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

			if (load == null) {
				test = useReady;
			}

			waitForOutput(test);
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

			waitForOutput(useReady);

			ready = true;
			if (fileHandling)
				setupFiles();
			startupTime = System.currentTimeMillis() - startupTime;
		}

		private void waitForOutput(String test) {
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
			if (!fileHandling) {
				return new LinkedList<File>();
			}
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

		/**
		 * @return the output of executing the command, up to, but not including
		 * killString.
		 */
		public String getOutput() {
			String out = output.currentValue();
			if (out.indexOf("\"" + killString) > 0) {
				return out.substring(0, out.indexOf("\"" + killString));
			} else if (out.indexOf(killString) > 0) {
				return out.substring(0, out.indexOf(killString));
			} else {
				return out;
			}
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

	void destroy() {
		// Kill the upkeep thread.
		try {
			upKeep.stopRunning();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
		}

		// Kill all running processes.
		for (MaximaProcess mp : pool) {
			mp.kill();
		}
		pool.clear();
	}
}
