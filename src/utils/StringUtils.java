/* Copyright (C) 2007 The Open University

   This program is free software; you can redistribute it and/or
   modify it under the terms of the GNU General Public License
   as published by the Free Software Foundation; either version 2
   of the License, or (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software
   Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package utils;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Utilities related to strings and string formatting */
public abstract class StringUtils {
	private static final long MINUTE = 60*1000;
	private static final long HOUR = 60*MINUTE;
	private static final long DAY = 24*HOUR;

	/**
	 * @param iBytes Number of bytes
	 * @return String containing size and unit in sensible units
	 */
	public static String formatBytes(int iBytes) {
		if (iBytes < 1024) {
			return iBytes + " bytes";
		}
		if (iBytes < 1024*1024) {
			return formatOneDecimal(iBytes, 1024) + " KB";
		}
		if (iBytes < 1024*1024*1024) {
			return formatOneDecimal(iBytes, 1024*1024) + " MB";
		}
		return formatOneDecimal(iBytes, 1024*1024*1024) + " GB";
	}

	/**
	 * @param lBytes Number of bytes
	 * @return String containing size and unit in sensible units
	 */
	public static String formatBytes(long lBytes) {
		return formatBytes((int) lBytes);
	}

	/** Format used for numbers to one DP. */
	private static final NumberFormat ONEDP = new DecimalFormat("#########0.0");

	/**
	 * @param iNumber
	 * @param iDivisor
	 * @return a string that is iNumber/iDivisor to one DP.
	 */
	public static String formatOneDecimal(int iNumber, int iDivisor) {
		if (iDivisor == 0) {
			return "??";
		}
		return formatOneDecimal(iNumber / (double) iDivisor);
	}

	 /**
	 * @param dNumber
	 * @return Formats the number to one DP.
	 */
	public static String formatOneDecimal(double dNumber) {
		return ONEDP.format(dNumber);
	}

	/**
	 * @param date a specific date and time.
	 * @return that time, nicely formatted for output.
	 */
	public static String formatTimestamp(Date date) {
		return (new SimpleDateFormat("HH:mm yyyy-MM-dd")).format(date);
	}

	/**
	 * @param durationMillis a length of time in milliseconds.
	 * @return a nicely formatted string representation of that duration.
	 */
	public static String formatDuration(long durationMillis) {
		long[] intervals = new long[] {MINUTE, HOUR, DAY};
		String[] words = new String[] {" minute", " hour", " day"};

		StringBuffer result = new StringBuffer(100);
		boolean started = false;
		for (int i = intervals.length - 1; i >= 0; i--) {
			long num = durationMillis / intervals[i];
			if (num > 0) {
				if (started) {
					result.append(", ");
				}
				result.append(num);
				result.append(' ');
				result.append(words[i]);
				if (num > 1) {
					result.append('s');
				}
				started = true;
			}
		}

		return result.toString();
	}
}
