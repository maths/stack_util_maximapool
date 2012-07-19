package fi.aalto.maximapool;


/**
 * A thread that starts one process and adds it to the pool.
 */
class ProcessStarter extends Thread {

	/** The MaximaPool we are starting a process for. */
	private final MaximaPool maximaPool;

	/** Used to generate unique thread names. */
	static private long startCount = 0;

	/**
	 * @param maximaPool the MaximaPool we are starting a process for.
	 */
	public ProcessStarter(MaximaPool maximaPool) {
		super();
		this.maximaPool = maximaPool;
		startCount++;
		this.setName(Thread.currentThread().getName() + "-starter-" + startCount);
	}

	@Override
	public void run() {
		maximaPool.notifyStartingProcess();
		MaximaProcess mp = new MaximaProcess(maximaPool.processBuilder, maximaPool.config);
		maximaPool.notifyProcessReady(mp);
	}
}
