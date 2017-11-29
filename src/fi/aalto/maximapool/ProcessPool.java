package fi.aalto.maximapool;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;


/**
 * A pool of available processes that all use a given configuration.
 *
 * This class is responsible for triggering the creationg of these processes,
 * and storing them when ready. It also tracks the demand for this type of
 * process.
 */
public class ProcessPool {

	/**
	 * The configuration for the processes we look after.
	 */
	private ProcessConfiguration processConfiguration;

	/**
	 * Used to create processes of our type..
	 */
	private ProcessBuilder processBuilder;

	/**
	 * Estimated startup time (ms).
	 */
	private long startupTimeEstimate = 2000;

	/**
	 * Estimated request frequency (Hz).
	 */
	private double demandEstimate = 0.001;

	/**
	 * The pool for ready processes.
	 */
	private BlockingDeque<MaximaProcess> availableProcesses = new LinkedBlockingDeque<MaximaProcess>();

	/**
	 * The number of processes this pool has created, just for reporting.
	 */
	private long processesStartedCount = 0;

	/**
	 * The last few startup times, used to compute startupTimeEstimate.
	 */
	private List<Long> startupTimeHistory = Collections
			.synchronizedList(new LinkedList<Long>());

	/**
	 * The last few request times, used to compute demandEstimate.
	 */
	private List<Long> requestTimeHistory = Collections
			.synchronizedList(new LinkedList<Long>());

	/**
	 * Constructor.
	 * @param processConfig the configuration for the processes we create.
	 */
	ProcessPool(ProcessConfiguration processConfig) {

		this.processConfiguration = processConfig;

		// Initialise the datasets.
		startupTimeHistory.add(processConfig.startupTimeInitialEstimate);
		requestTimeHistory.add(System.currentTimeMillis());

		// Set up the processBuilder
		processBuilder = new ProcessBuilder();
		processBuilder.command(processConfig.commandLine.split(" "));
		processBuilder.directory(processConfig.workingDirectory);
		processBuilder.redirectErrorStream(true);
		for (String key : processConfig.environment.keySet()) {
			processBuilder.environment().put(key, processConfig.environment.get(key));
		}
	}

	/**
	 * Kill all running processes. After calling this method this class cannot
	 * be used any more.
	 */
	void destroy() {
		for (MaximaProcess maximaProcess : availableProcesses) {
			maximaProcess.kill();
		}
		availableProcesses.clear();

		// Signal that we are destroyed. Stops more processes being added.
		availableProcesses = null;
	}

	/**
	 * Get a MaximaProcess from the pool.
	 */
	MaximaProcess getProcess() {
		requestTimeHistory.add(System.currentTimeMillis());

		MaximaProcess maximaProcess = null;
		while (maximaProcess == null) {
			try {
				maximaProcess = availableProcesses.take();
			} catch (InterruptedException e) {
				// If we failed to get one, wait a bit.
				e.printStackTrace();
				try {
					Thread.sleep(3);
				} catch (InterruptedException ee) {
					ee.printStackTrace();
				}
			}
		}

		return maximaProcess;
	}

	/**
	 * Low-level that creates a process in the current thread, and does not add
	 * it to the pool.
	 * @return the new process.
	 */
	MaximaProcess makeProcess() {
		return new MaximaProcess(processBuilder, processConfiguration);
	}

	/**
	 * Start a process asynchronously, and add it to the pool when done.
	 * @return the new process.
	 */
	void startProcess() {
		processesStartedCount++;
		long startTime = System.currentTimeMillis();
		MaximaProcess mp = makeProcess();
		startupTimeHistory.add(System.currentTimeMillis() - startTime);
		mp.deactivate();
		availableProcesses.add(mp);
	}

	/**s
	 * Maintenance task that detects stale processes that should be killed.
	 * @param testTime time to consider as now.
	 */
	void killOverdueProcesses(long testTime) {

		// Kill off old ones
		MaximaProcess process = availableProcesses.poll();
		while (process != null && process.isOverdue(testTime)) {
			process.kill();
			process = availableProcesses.poll();
		}
		if (process != null) {
			availableProcesses.addFirst(process);
		}
	}

	/**
	 * Maintenance task that updates the estimates that are used to manaage the pool.
	 * @param dataPointsToKeep lenght at which to trim the moving averages.
	 */
	void updateDemandEstimate(int dataPointsToKeep) {
		// Do estimates
		long totalTime = 0;
		for (long t : startupTimeHistory) {
			totalTime += t;
		}
		startupTimeEstimate = totalTime / startupTimeHistory.size();

		// Math.max(..., 1) to avoid divide by zeros.
		demandEstimate = 1000.0 * requestTimeHistory.size()
				/ Math.max(System.currentTimeMillis() - requestTimeHistory.get(0), 1.0);

		// Prune datasets
		while (startupTimeHistory.size() > dataPointsToKeep) {
			startupTimeHistory.remove(0);
		}
		while (requestTimeHistory.size() > dataPointsToKeep) {
			requestTimeHistory.remove(0);
		}
	}

	/**
	 * Get the demand estimate.
	 * @return frequency, in processes per second.
	 */
	public double getDemandEstimate() {
		return demandEstimate;
	}

	public int getAvailableProcessesCount() {
		return availableProcesses.size();
	}

	/**
	 * Return information about the current state of this pool.
	 * @return a hash map where the keys are human-readable names,
	 * and the values are string representations of those values.
	 */
	Map<String, String> getStatus() {

		Map<String, String> status = new LinkedHashMap<String, String>();

		status.put("Ready processes in the pool", "" + availableProcesses.size());
		status.put("Total processes started", "" + processesStartedCount);
		status.put("Current demand estimate", demandEstimate + " Hz");
		status.put("Current start-up time estimate", startupTimeEstimate + " ms");

		StringBuffer startupTimes = new StringBuffer(100);
		for (long time : startupTimeHistory) {
			startupTimes.append(time);
			startupTimes.append(" ms ");
		}
		status.put("Recent start-up times", startupTimes.toString());

		DateFormat df = new SimpleDateFormat("HH:mm:ss ");
		StringBuffer requestTimes = new StringBuffer(100);
		for (long time : requestTimeHistory) {
			requestTimes.append(df.format(new Date(time)));
		}
		status.put("Recent request times", requestTimes.toString());

		return status;
	}

	/**
	 * Get the configuration we are using.
	 * @return the configuration.
	 */
	public ProcessConfiguration getProcessConfiguration() {
		return processConfiguration;
	}
}
