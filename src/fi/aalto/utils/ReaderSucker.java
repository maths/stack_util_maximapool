package fi.aalto.utils;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.Semaphore;

/**
 * This utility accumulates all the input so far from a reader into a
 * StringBuffer so that it can easily be accessed.
 *
 * @author Matti Harjula
 */
public class ReaderSucker {

	/** All the input from the reader is accumulated here. */
	private StringBuffer value = new StringBuffer();

	/** The reader we are reading from. */
	private Reader reader;

	/** Records when the end of the input is detected. */
	private volatile boolean foundEnd = false;

	/** We only read from the reader when we have a token from this Semaphore. */
	private Semaphore runSwitch;

	/**
	 * @param source the Reader to read from.
	 * @param runSwitch the run switch to use.
	 */
	public ReaderSucker(Reader source, Semaphore runSwitch) {
		reader = source;
		this.runSwitch = runSwitch;
		start();
	}

	/** Start the worker thread. */
	private void start() {
		String threadName = Thread.currentThread().getName().replace("-starter-", "-readersucker-");
		Thread worker = new Thread(threadName) {
			public void run() {
				char[] buffer = new char[1024];
				int i = 0;
				while (!foundEnd) {
					try {
						runSwitch.acquire();
					} catch (InterruptedException e) {
					}
					i = 0;
					try {
						if (reader.ready()) {
							i = reader.read(buffer);
						}
					} catch (IOException e) {
					}
					runSwitch.release();
					if (i > 0) {
						value.append(new String(buffer, 0, i));
					} else if (i == -1) {
						foundEnd = true;
						break;
					}

					// Sleep here so the stream does not get too much attention.
					try {
						Thread.sleep(0, 100);
					} catch (InterruptedException e) {
					}
				}
				try {
					if (foundEnd) {
						reader.close();
					}
				} catch (IOException e) {
				}
			}

		};
		worker.setName(Thread.currentThread().getName() + "-reader");
		worker.start();
	}

	/**
	 * Cleanup method.
	 */
	public void close() {
		foundEnd = true;
		try {
			reader.close();
		} catch (IOException e) {
		}
	}

	/**
	 * @return all the output so far.
	 */
	public String currentValue() {
		return value.toString();
	}

	/**
	 * @return Whether we have got to the end of the input.
	 */
	public boolean isAtEnd() {
		return foundEnd;
	}
}
