package fi.aalto.maximapool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import fi.aalto.utils.UpkeepThread;


/**
 * This class coordinates a number of different processe pools. It provides
 *  - a facade for asking for a process by configuration name.
 *  - load balancing between the different pools, controlling the number of
 *    processes of each type to keep around.
 *  - a way to start or stop the pool for a particular configuration.
 */
public class PoolCoordinator implements UpkeepThread.Maintainable {

	/**
	 * The configuration for the set of pools as a whole.
	 */
	private PoolConfiguration poolConfiguration;

	/**
	 * The pools we are managing, keyd by configuration name.
	 */
	private ConcurrentHashMap<String, ProcessPool> processPools =
			new ConcurrentHashMap<String, ProcessPool>();

	/**
	 * The maintenance thread.
	 */
	private UpkeepThread upKeep;

	/**
	 * Used to generate unique thread names.
	 */
	static private long startCount = 0;

	/**
	 * Used to restrict the number of processes starting up at any one time.
	 */
	private volatile Semaphore startupThrottle;

	/**
	 * The pool of processes currently being used. These will be thrown away
	 * when finshed.
	 */
	private List<MaximaProcess> usedPool = Collections
			.synchronizedList(new LinkedList<MaximaProcess>());

	/**
	 * Constructor.
	 * @param poolConfig the configuration for the pool.
	 * @param processConfig the configuration for the processes we create.
	 */
	PoolCoordinator(PoolConfiguration poolConfig) {

		poolConfiguration = poolConfig;
		startupThrottle = new Semaphore(poolConfiguration.startupLimit);

		// Start the upkeep thread.
		upKeep = new UpkeepThread("MaximaPool-upkeep", this, poolConfiguration.maintenanceCycleTime);
		upKeep.start();
	}

	/**
	 * Start a pool for the named configuration, if one does not already exist.
	 * @param configurationName the configuration to start.
	 */
	void startConfiguration(String configurationName) {
		if (processPools.containsKey(configurationName)) {
			return;
		}

		ProcessConfiguration processConfiguration = poolConfiguration.processConfigurations.get(configurationName);
		if (poolConfiguration == null) {
			throw new RuntimeException("Cannot start a pool for unknown configuration " + configurationName);
		}

		ProcessPool pool = new ProcessPool(processConfiguration);
		ProcessPool existing = processPools.putIfAbsent(configurationName, pool);
		if (existing != null) {
			// This may happen if there was a race condition. Someone else got
			// there first, so just descard the pool we created.
			pool.destroy();
		}
	}

	/**
	 * Stop the pool for the named configuration, if it is running.
	 * @param configurationName the configuration to stop.
	 */
	void stopConfiguration(String configurationName) {
		ProcessPool pool = processPools.remove(configurationName);
		if (pool != null) {
			pool.destroy();
		}
	}

	/**
	 * Look on disc for all the child directories of directoryRoot, to find the
	 * processes configurations that we can run.
	 */
	void scanConfigurations() {
		poolConfiguration.scanAvailableProcessConfigurations();
	}

	/**
	 * Stop all the pools we manage and destroy this object. After calling this
	 * method this class cannot be used any more.
	 */
	void destroy() {
		// Kill the upkeep thread.
		try {
			upKeep.stopRunning();
		} catch (InterruptedException e) {
		}
		upKeep = null;

		// Kill all running process pools.
		for (String configurationName : processPools.keySet()) {
			stopConfiguration(configurationName);
		}
		processPools = null;

		// Kill all used processes.
		for (MaximaProcess mp : usedPool) {
			mp.kill();
		}
		usedPool.clear();
		usedPool = null;
	}

	/**
	 * Given a requested configuration, find the best matching runnging pool.
	 * This should not be used for operations for starting and stopping pools,
	 * just when getting a processes, when a reasonable match is better that a
	 * failure.
	 * @param requestedConfigurationName the configuration desired.
	 * @return the name of a running configuration.
	 */
	private String getBestMatchingPoolName(String requestedConfigurationName) {
		if (requestedConfigurationName != null && processPools.containsKey(requestedConfigurationName)) {
			// Exact match. Good.
			return requestedConfigurationName;
		}
		
		List<String> runningConfigurations = getRunningConfigurations();
		if (requestedConfigurationName == null) {
			// Specific configuration not requested. This should be a legacy
			// situation, so return the oldest running configuration.
			return runningConfigurations.get(0);
		}

		// Return the most recent configuration older than what was requested.
		String bestMatch = runningConfigurations.get(0);
		for (String possibleName : runningConfigurations) {
			if (requestedConfigurationName.compareTo(possibleName) < 0) {
				break;
			}
			bestMatch = possibleName;
		}
		return bestMatch;
	}

	/**
	 * Get a MaximaProcess from the pool. If the desired configuration is not
	 * avialable, the best available match will be used.
	 * @param requestedConfigurationName the configuration desired.
	 * @return a process.
	 */
	MaximaProcess getProcess(String requestedConfigurationName) {
		String configurationName = getBestMatchingPoolName(requestedConfigurationName);

		// Start a new one as we are going to take one...
		if (startupThrottle.availablePermits() > 0) {
			startProcess(configurationName);
		}

		MaximaProcess maximaProcess = processPools.get(configurationName).getProcess();

		usedPool.add(maximaProcess);
		maximaProcess.activate();
		return maximaProcess;
	}

	/**
	 * Low-level that creates a process in the current thread, and does not add
	 * it to the pool.
	 * @return the new process.
	 */
	MaximaProcess makeProcess(String configurationName) {
		return processPools.get(configurationName).makeProcess();
	}

	/**
	 * Start a process asynchronously, and add it to the pool when done.
	 * @return the new process.
	 */
	private void startProcess(String configurationName) {
		startCount++;
		final ProcessPool pool = processPools.get(configurationName);
		String threadName = Thread.currentThread().getName() + "-starter-" + startCount;
		Thread starter = new Thread(threadName) {
			@Override
			public void run() {
				try {
					startupThrottle.acquireUninterruptibly();
					pool.startProcess();
				} finally {
					startupThrottle.release();
				}
			}
		};
		starter.start();
	}

	/**
	 * Use to tell us that a particular process has finished.
	 * @param process the process that has finished.
	 */
	void notifyProcessFinishedWith(MaximaProcess process) {
		usedPool.remove(process);
	}

	@Override
	public void doMaintenance(long sleepTime) {
		killOverdueProcesses();
		updateEstimates();
		startMoreProcessesIfRequired();
	}

	/**
	 * Maintenance task that detects and kills stale processes in each pool.
	 */
	private void killOverdueProcesses() {
		long testTime = System.currentTimeMillis();

		// Kill stale processes from all process pools.
		for (ProcessPool pool : processPools.values()) {
			pool.killOverdueProcesses(testTime);
		}

		// Kill stale processes that are being used, but have timed out.
		while (!usedPool.isEmpty() && usedPool.get(0).isOverdue(testTime)) {
			usedPool.remove(0).close();
		}
	}

	/**
	 * Maintenance task that updates the estimates that are used to manaage the pool.
	 */
	private void updateEstimates() {
		for (ProcessPool pool : processPools.values()) {
			pool.updateDemandEstimate(poolConfiguration.movingAverageDataPoints);
		}
	}

	/**
	 * Start up as many processes as may be required to get the pool to the level
	 * it should be at.
	 */
	private void startMoreProcessesIfRequired() {
		// TODO improve the sophistication of this. Git history has
		// the algorithm it used to use when there was only one pool.
		int poolCount = processPools.size();
		for (Map.Entry<String, ProcessPool> entry : processPools.entrySet()) {
			if (startupThrottle.availablePermits() == 0) {
				break;
			}

			ProcessPool pool = entry.getValue();
			if (pool.getAvailableProcessesCount() < poolConfiguration.minimumAvailableProcesses / poolCount + 1) {
				startProcess(entry.getKey());
			}
		}
	}

	/**
	 * Get the pool configurations that are available on disc (or were, last
	 * time we checked).
	 * @return configuration name => configuration.
	 */
	Map<String, ProcessConfiguration> getAvailablePoolConfigurations() {
		return Collections.unmodifiableMap(poolConfiguration.processConfigurations);
	}

	/**
	 * Get the configuration used by a particular running pool.
	 * @param configurationName the pool configuration name.
	 * @return the configuration.
	 */
	ProcessConfiguration getProcessConfiguration(String configurationName) {
		return poolConfiguration.processConfigurations.get(configurationName);
	}

	/**
	 * Check a running configuration agains the configuration on disc (last time
	 * we checked what was on disc.)
	 * @param configurationName the name of a running configuration.
	 * @return whether the configuration being run is the same as on disc.
	 */
	boolean isConfigurationCurrent(String configurationName) {
		return processPools.get(configurationName).getProcessConfiguration().equals(
				getProcessConfiguration(configurationName));
	}

	/**
	 * Get the configuration names of all the running configurations, in order.
	 * @return
	 */
	List<String> getRunningConfigurations() {
		List<String> configurationNames = new ArrayList<String>(processPools.keySet());
		Collections.sort(configurationNames);
		return configurationNames;
	}

	/**
	 * Return information about the current state of a running pool.
	 * @param configurationName the name of a running configuration.
	 * @return a hash map where the keys are human-readable names, and,
	 * and the values are string representations of those values.
	 */
	Map<String, String> getPoolStatus(String configurationName) {
		return processPools.get(configurationName).getStatus();
	}

	/**
	 * Return information about the current state of the collection of pools.
	 * @return a hash map where the keys are human-readable names,
	 * and the values are string representations of those values.
	 */
	Map<String, String> getStatus() {

		Map<String, String> status = new LinkedHashMap<String, String>();

		status.put("Processes starting up", "" +
				(poolConfiguration.startupLimit - startupThrottle.availablePermits()));
		status.put("Processes in use", "" + usedPool.size());
		status.put("Total number of processes started", "" + startCount);

		return status;
	}

	/**
	 * Describe the configuration of the collection of pools.
	 * @return a hash map where the keys are human-readable names,
	 * and the values are string representations of those values.
	 */
	Map<String, String> describeConfiguration() {
		return poolConfiguration.describe();
	}
}
