package fi.aalto.maximapool;

import java.io.IOException;
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
public class MaximaPool implements UpkeepThread.Maintainable {

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
	MaximaProcessConfig config;

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

		config = new MaximaProcessConfig(properties);

		// Initialise the datasets.
		startupTimeHistory.add(startupTimeEstimate);
		requestTimeHistory.add(System.currentTimeMillis());

		// Set up the processBuilder
		processBuilder.command(config.cmdLine.split(" "));
		processBuilder.directory(config.cwd);
		processBuilder.redirectErrorStream(true);

		// Create the startup throttle.
		this.startupThrotle = new Semaphore(config.startupLimit);

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
		status.put("Maxima command-line", config.cmdLine);
		if (config.load != null) {
			try {
				status.put("File to load", config.load.getCanonicalPath());
			} catch (IOException e) {
			}
		}
		status.put("Started test string", config.loadReady);
		status.put("Loaded test string", config.useReady);
		status.put("File handling", config.fileHandling ? "On" : "Off");
		status.put("File paths template", config.pathCommandTemplate);
		status.put("Min pool size", "" + poolMin);
		status.put("Max pool size", "" + poolMax);
		status.put("Pool update cycle time", updateCycle + " ms");
		status.put("Number of data points for averages", "" + averageCount);
		status.put("Pool size safety multiplier", "" + safetyMultiplier);
		status.put("Execution extra time limit", config.executionTime + " ms");
		status.put("Process life time limit", config.lifeTime + " ms");

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
		mp.liveTill += config.executionTime;
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
				config.startupLimit - startupThrotle.availablePermits();

		while (numProcesses < numProcessesRequired
				&& startupThrotle.availablePermits() > 0) {
			numProcesses += 1.0;
			ProcessStarter starter = new ProcessStarter(this);
			starter.start();
		}
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

	@Override
	public void doMaintenance(long sleepTime) {
		killOverdueProcesses();
		double numProcessesRequired = updateEstimates(sleepTime);
		startProcesses(numProcessesRequired);
	}
}
