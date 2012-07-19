package fi.aalto.maximapool;

class UpkeepThread extends Thread {
	/** The pool we are managing the upkeep of. */
	private final MaximaPool maximaPool;

	/** Time to sleep between each bit of maintenance. */
	private long sleep;

	/** Used to signal to this thread that it should die. */
	private volatile boolean stopNow = false;

	UpkeepThread(MaximaPool maximaPool, long sleepTime) {
		super();
		setName("MaximaPool-upkeep");
		setDaemon(true);
		this.maximaPool = maximaPool;
		sleep = sleepTime;
	}

	void stopRunning() throws InterruptedException {
		stopNow = true;
		join();
	}

	public void run() {
		while (!stopNow) {
			maximaPool.killOverdueProcesses();
			double numProcessesRequired = maximaPool.updateEstimates(sleep);
			maximaPool.startProcesses(numProcessesRequired);

			try {
				Thread.sleep(sleep);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}