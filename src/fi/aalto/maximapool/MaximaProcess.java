package fi.aalto.maximapool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;

class MaximaProcess {

	public static final long STARTUP_TIMEOUT = 10000; // miliseconds

	MaximaProcessConfig config;

	Process process = null;

	File baseDir = null;

	boolean ready = false;
	long startupTime = -1;
	long liveTill;

	OutputStreamWriter input = null;
	ReaderSucker output = null;

	Semaphore runSwitch = new Semaphore(1);

	/**
	 * This constructor blocks till it is ready so create in a thread...
	 */
	MaximaProcess(ProcessBuilder processBuilder, MaximaProcessConfig config) {
		this.config = config;

		startupTime = System.currentTimeMillis();
		liveTill = System.currentTimeMillis() + config.lifeTime;

		try {
			process = processBuilder.start();
		} catch (IOException e) {
			System.out.println("Process startup failure...");
			e.printStackTrace();
			return;
		}

		output = new ReaderSucker(new BufferedReader(
				new InputStreamReader(new BufferedInputStream(process
						.getInputStream()))), runSwitch);
		input = new OutputStreamWriter(new BufferedOutputStream(process
				.getOutputStream()));

		String test = config.loadReady;

		if (config.load == null) {
			test = config.useReady;
		}

		waitForOutput(test);
		if (config.load == null) {
			ready = true;
			if (config.fileHandling)
				setupFiles();
			startupTime = System.currentTimeMillis() - startupTime;
			return;
		}

		try {
			String command = "load(\""
					+ config.load.getCanonicalPath()
							.replaceAll("\\\\", "\\\\\\\\") + "\");\n";
			input.write(command);

			input.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}

		waitForOutput(config.useReady);

		ready = true;
		if (config.fileHandling)
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
			if (System.currentTimeMillis() > startupTime + config.startupTime) {
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

			String command = config.pathCommandTemplate;
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
	 * @param timeout limit in ms
	 * @return
	 */
	boolean doAndDie(String command, long timeout) {
		String killStringGen = "concat(\""
				+ config.killString.substring(0, config.killString.length() / 2)
				+ "\",\"" + config.killString.substring(config.killString.length() / 2)
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
			if (output.currentValue().contains(config.killString)) {
				output.close();
				kill();
				return true;
			}

			if (processDone) {
				readDone = output.isAtEnd();
			}

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
		if (!config.fileHandling) {
			return new LinkedList<File>();
		}
		return FileUtils.listFiles(new File(baseDir, "output"));
	}

	@Override
	protected void finalize() throws Throwable {
		kill();
		if (config.fileHandling) {
			FileUtils.deleteDirectoryRecursive(baseDir);
		}

		super.finalize();
	}

	/**
	 * @return the output of executing the command, up to, but not including
	 * killString.
	 */
	public String getOutput() {
		String out = output.currentValue();
		if (out.indexOf("\"" + config.killString) > 0) {
			return out.substring(0, out.indexOf("\"" + config.killString));
		} else if (out.indexOf(config.killString) > 0) {
			return out.substring(0, out.indexOf(config.killString));
		} else {
			return out;
		}
	}
}