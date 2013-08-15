package fi.aalto.utils;


/**
 * This class will run in the background, and call the doMaintenance method
 * of a UpkeepThread.Maintainable object with a specified frequency.
 */
public class UpkeepThread extends Thread {
	/** The thing we are managing the upkeep of. */
	private final Maintainable target;

	/** Time to sleep between each bit of maintenance. */
	private long sleep;

	/** Used to signal to this thread that it should die. */
	private volatile boolean stopNow = false;

	/**
	 * @param name a thread name.
	 * @param thingToMaintain the object to maintain
	 * @param sleepTime the time to wait between each call to doMaintenance in milliseconds.
	 */
	public UpkeepThread(String name, Maintainable thingToMaintain, long sleepTime) {
		super(name);
		setDaemon(true);
		this.target = thingToMaintain;
		sleep = sleepTime;
	}

	/**
	 * Calling this method will request that the thread stops at the next convenient moment.
	 * It blocks until the thread has stopped.
	 * @throws InterruptedException
	 */
	public void stopRunning() throws InterruptedException {
		stopNow = true;
		join();
	}

	@Override
	public void run() {
		while (!stopNow) {
			try {
				target.doMaintenance(sleep);
			} catch (Exception e) {
				e.printStackTrace();
			}

			try {
				Thread.sleep(sleep);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * This is the interface that classes that wish to be maintained must implement.
	 */
	public interface Maintainable {
		public void doMaintenance(long sleepTime);
	}
}
