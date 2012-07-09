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
package fi.aalto.maximapool;

import java.text.DecimalFormat;
import java.text.NumberFormat;

/** Utilities related to strings and string formatting */
public class StringUtils {
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
}
