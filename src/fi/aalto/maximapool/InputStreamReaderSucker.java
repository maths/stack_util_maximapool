package fi.aalto.maximapool;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.Semaphore;

/**
 * A utility for fast reading of STDOUT & STDERR...
 *
 * @author Matti Harjula
 */
class InputStreamReaderSucker {

	/** All the ouptut from the reader is accumulated here. */
	StringBuffer value = new StringBuffer();

	/** The reader we are reading from. */
	Reader reader = null;

	/** Records when the end of the input is detected. */
	volatile boolean foundEnd = false;

	Semaphore runSwitch;

	/**
	 * @param source the Reader to read from.
	 * @param runSwitch the run switch to use.
	 */
	InputStreamReaderSucker(Reader source, Semaphore runSwitch) {
		reader = source;
		this.runSwitch = runSwitch;
		start();
	}

	private void start() {
		Thread worker = new Thread() {
			public void run() {
				char[] buffy = new char[1024];
				int i = 0;
				while (!foundEnd) {
					try {
						runSwitch.acquire();
					} catch (InterruptedException e1) {
						e1.printStackTrace();
					}
					i = 0;
					try {
						if (reader.ready()) {
							i = reader.read(buffy);
						}
					} catch (IOException e) {
					}
					runSwitch.release();
					if (i > 0) {
						value.append(new String(buffy, 0, i));
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
	void close() {
		foundEnd = true;
		try {
			reader.close();
		} catch (IOException e) {
		}
	}

	/**
	 * @return all the output so far.
	 */
	String currentValue() {
		return value.toString();
	}
}
