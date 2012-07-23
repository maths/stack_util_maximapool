package fi.aalto.utils;

import java.io.File;
import java.util.LinkedList;
import java.util.List;


/**
 * Various helper methods relating to files.
 */
public abstract class FileUtils {
	/**
	 * Lists all the files in a folder and all its subfolders.
	 *
	 * @param baseDir the directory to enumerate.
	 * @return the requested list of files.
	 */
	public static List<File> listFiles(File baseDir) {
		List<File> files = new LinkedList<File>();

		for (File f : baseDir.listFiles()) {
			if (f.isDirectory()) {
				files.addAll(listFiles(f));
			} else {
				files.add(f);
			}
		}

		return files;
	}

	/**
	 * Delete a directory and everything inside it.
	 * @param baseDir the directory to delete.
	 */
	public static void deleteDirectoryRecursive(File baseDir) {
		for (File f : baseDir.listFiles()) {
			if (f.isDirectory()) {
				deleteDirectoryRecursive(f);
			} else {
				f.delete();
			}
		}
		baseDir.delete();
	}
}
