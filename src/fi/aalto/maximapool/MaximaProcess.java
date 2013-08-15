package fi.aalto.maximapool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import fi.aalto.utils.FileUtils;
import fi.aalto.utils.ReaderSucker;


/**
 * A single maxima process.
 * The class deals with the mechanicas of starting a process, putting it to
 * sleep, waking it, feeding it input and collecing the output.
 */
class MaximaProcess {

	/** The configuration that determines how the process should be. */
	private ProcessConfiguration configuration;

	/** The actual operating system process. */
	private Process process = null;

	/** If we are handling files, the top level folder where the files go. */
	private File generatedFileDir = null;

	/** Expiry time. If this time passes, the process is forcibly killed. */
	private long liveUntil;

	/** Connected to the STD input of the process. */
	private OutputStreamWriter processInput = null;

	/** Connected to the STD output of the process. */
	private ReaderSucker processOutput = null;

	/**
	 * Semaphore for whether the process is active. Used to control the ReaderSucker
	 * When we acquire the run switch, the ReaderSucker goes to sleep.
	 */
	private Semaphore runSwitch = new Semaphore(1);

	/**
	 * This constructor blocks till it is ready so create in a thread...
	 */
	MaximaProcess(ProcessBuilder processBuilder, ProcessConfiguration config) {
		configuration = config;

		liveUntil = System.currentTimeMillis() + config.startupTimeout;

		try {
			process = processBuilder.start();
		} catch (IOException e) {
			System.out.println("Process startup failure...");
			e.printStackTrace();
			return;
		}

		processOutput = new ReaderSucker(new BufferedReader(
				new InputStreamReader(new BufferedInputStream(process
						.getInputStream()))), runSwitch);
		processInput = new OutputStreamWriter(new BufferedOutputStream(process
				.getOutputStream()));

		String test = config.processHasStartedOutput;

		if (config.extraFileToLoad == null) {
			test = config.processIsReadyOutput;
		}

		waitForOutput(test);
		if (config.extraFileToLoad == null) {
			liveUntil = System.currentTimeMillis() + config.maximumLifetime;
			return;
		}

		try {
			String command = "load(\""
					+ config.extraFileToLoad.getCanonicalPath()
							.replaceAll("\\\\", "\\\\\\\\") + "\");\n";
			processInput.write(command);

			processInput.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}

		waitForOutput(config.processIsReadyOutput);

		liveUntil = System.currentTimeMillis() + config.maximumLifetime;
	}

	/**
	 * Deactivate the process. This is called once the processes has started up
	 * and is ready to recieve input, and so is about to be added to the waiting
	 * list of ready processes in the pool.
	 */
	void deactivate() {
		try {
			runSwitch.acquire();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Activate the process. This is called when the process is taken out of the
	 * pool and is about to be used.
	 */
	void activate() {
		liveUntil += configuration.executionTimeout;
		runSwitch.release(1);
	}

	/**
	 * Actually process a command.
	 *
	 * After calling this method, you should call MaximaPool.notifyProcessFinishedWith
	 * to tell the pool that this processes has died.
	 *
	 * @param command the command to execute.
	 * @param timeout limit in ms
	 * @param base URL that plot images will be served from.
	 * @return true if we did not timeout.
	 */
	boolean doAndDie(String command, long timeout, String plotUrlBase) {

		if (configuration.fileHandling) {
			setupFiles(plotUrlBase);
		}

		String killStringGen = "concat(\""
				+ configuration.killString.substring(0, configuration.killString.length() / 2)
				+ "\",\"" + configuration.killString.substring(configuration.killString.length() / 2)
				+ "\");";

		try {
			processInput.write(command + killStringGen + "quit();\n");
			processInput.close();
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		// Basic limit for catching hanged or too long runs
		liveUntil = timeout + System.currentTimeMillis();

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
			if (processOutput.currentValue().contains(configuration.killString)) {
				processOutput.close();
				kill();
				return true;
			}

			if (processDone) {
				readDone = processOutput.isAtEnd();
			}

			if (liveUntil < System.currentTimeMillis()) {
				processOutput.close();
				kill();
				return false;
			}

			if (readDone) {
				processOutput.close();
				return true;
			}

			// Read not done. Wait some more.
			try {
				Thread.sleep(0, 100);
			} catch (InterruptedException e) {
			}
		}
		processOutput.close();
		deactivate();
		return true;
	}

	/**
	 * Helper method. Waits until a particular string is detected in the output,
	 * before returning.
	 * @param test string to look for in the output.
	 */
	private void waitForOutput(String test) {
		while (processOutput.currentValue().indexOf(test) < 0) {
			try {
				Thread.sleep(0, 200);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			if (System.currentTimeMillis() > liveUntil) {
				throw new RuntimeException("Process timed out.");
			}
		}
	}

	/**
	 * @return the output of executing the command, up to, but not including killString.
	 */
	String getOutput() {
		String out = processOutput.currentValue();
		if (out.indexOf("\"" + configuration.killString) > 0) {
			return out.substring(0, out.indexOf("\"" + configuration.killString));
		} else if (out.indexOf(configuration.killString) > 0) {
			return out.substring(0, out.indexOf(configuration.killString));
		} else {
			return out;
		}
	}

	/**
	 * @param testTime the time to consider as now. Typically System.currentTimeMillis().
	 * @return whether testTime is after the liveTill time.
	 */
	boolean isOverdue(long testTime) {
		return liveUntil < testTime;
	}

	/**
	 * If the process is not already finished, kill it.
	 */
	void close() {
		try {
			process.exitValue();
		} catch (Exception e) {
			kill();
		}
	}

	/**
	 * Forcibly end this process.
	 */
	void kill() {
		runSwitch.release();
		processOutput.close();
		try {
			process.exitValue();
			return;
		} catch (Exception e) {
			// Nope just testing if it was already down
		}
		try {
			processInput.write("quit();\n\n");
			processInput.close();
		} catch (IOException e1) {
		}

		processOutput.close();
		try {
			process.exitValue();
		} catch (Exception e) {
			process.destroy();
		}
	}

	@Override
	protected void finalize() throws Throwable {
		kill();
		if (configuration.fileHandling) {
			FileUtils.deleteDirectoryRecursive(generatedFileDir);
		}

		super.finalize();
	}

	/**
	 * Set up file handling. This creates the directory, and sends a command
	 * to Maxima giving the path.
	 */
	private void setupFiles(String plotUrlBase) {
		try {
			generatedFileDir = File.createTempFile("mp-", "-" + process.hashCode());
			generatedFileDir.delete();
			generatedFileDir.mkdirs();
			File output = new File(generatedFileDir, "output");
			File work = new File(generatedFileDir, "work");
			output.mkdir();
			work.mkdir();

			String command = configuration.pathCommandTemplate;
			command = command.replaceAll("%OUTPUT-DIR-NE%",
					output.getCanonicalPath());
			command = command.replaceAll("%WORK-DIR-NE%",
					work.getCanonicalPath());
			command = command.replaceAll("%OUTPUT-DIR%",
					output.getCanonicalPath().replaceAll("\\\\", "\\\\\\\\"));
			command = command.replaceAll("%WORK-DIR%",
					work.getCanonicalPath().replaceAll("\\\\", "\\\\\\\\"));
			command = command.replaceAll("%PLOT-URL-BASE%", plotUrlBase);
			processInput.write(command);
			processInput.flush();
		} catch (IOException e) {
			System.out.println("File handling failure, maybe the securitymanager has something against us?");
			e.printStackTrace();
		}
	}

	/**
	 * @return a list of the files generated while executing the command, if any.
	 */
	List<File> filesGenerated() {
		if (!configuration.fileHandling) {
			return new LinkedList<File>();
		}
		return FileUtils.listFiles(new File(generatedFileDir, "output"));
	}

	/**
	 * Add the generated files to a given Zip stream.
	 * @param zos
	 * @throws IOException
	 */
	void addGeneratedFilesToZip(ZipOutputStream zos) throws IOException {
		byte[] buffy = new byte[4096];

		for (File f : filesGenerated()) {
			String name = f.getCanonicalPath().replace(
					new File(generatedFileDir, "output").getCanonicalPath(), "");
			ZipEntry z = new ZipEntry(name);
			zos.putNextEntry(z);
			FileInputStream fis = new FileInputStream(f);
			int c = fis.read(buffy);
			while (c > 0) {
				zos.write(buffy, 0, c);
				c = fis.read(buffy);
			}
			fis.close();
			zos.closeEntry();
		}
	}
}
