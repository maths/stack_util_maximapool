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
package fi.aalto.utils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;


/** Utilities related to outputting HTML. */
public abstract class HtmlUtils {

	/**
	 * Replace HTML special chars in a string.
	 * @param input
	 * @return
	 */
	public static String escape(String input) {
		return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").
				replace("'", "&#039;").replace("\"", "&quot;");
	}

	/**
	 * Helper method to start displaying a HTML response.
	 * @param response the response to send the HTML head to.
	 * @return a so you can continue the output.
	 * @throws IOException
	 */
	public static PrintWriter startOutput(HttpServletResponse response, String title) throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html; charset=UTF-8");

		PrintWriter out = response.getWriter();

		out.write("<!DOCTYPE html><html><head>" +
				"<title>MaximaPool - </title>" +
				"<style type=\"text/css\">" +
				"div.pool { margin: 1em 0; padding: 1em; box-shadow: inset 0 0 1em #aaa; border-radius: 0.7em; }" +
				"table { margin: 0.5em 0; border-radius: 0.5em; }" +
				"th, td { padding: 0 0.5em; text-align: left; }" +
				"th { background: #ddd; }" +
				"tr.even td { background: #ddd; }" +
				"tr.odd td { background: #eee; }" +
				"p { margin: 0 0 0.5em; }" +
				"pre { padding: 0.5em; background: #eee; }" +
				"pre.command { background: #dfd; }" +
				".warning { color: #800; }" +
				"h1 { margin: 1em 0 0.5em; font-size: 1.4em; }" +
				"h2 { margin: 1em 0 0.5em; font-size: 1.2em; }" +
				"h2:first-child { margin-top: 0; }" +
				"</style>" +
				"</head><body>");
		return out;
	}

	/**
	 * Finish the output and close the Writer.
	 * @param out where to send the output.
	 * @throws IOException
	 */
	public static void finishOutput(Writer out) throws IOException {
		out.write("</body></html>");
		out.close();
	}	

	/**
	 * Write a start div tag with a given class name.
	 * @param out Writer to write to.
	 * @param className the class attribute value.
	 * @param id an id to add to the HTML.
	 * @throws IOException
	 */
	public static void writeDivStart(Writer out, String className, String id) throws IOException {
		out.write("<div class=\"" + escape(className) + "\" id=\"" + escape(id) + "\">");
	}

	/**
	 * Write a close div tag.
	 * @param out Writer to write to.
	 * @throws IOException
	 */
	public static void writeDivEnd(Writer out) throws IOException {
		out.write("</div>");
	}

	/**
	 * Write a string surrounded by h1 tags.
	 * @param out Writer to write to.
	 * @param heading the text to write.
	 * @throws IOException
	 */
	public static void writeHeading(Writer out, String heading) throws IOException {
		out.write("<h1>" + escape(heading) + "</h1>");
		out.flush();
	}

	/**
	 * Write a string surrounded by h2 tags.
	 * @param out Writer to write to.
	 * @param heading the text to write.
	 * @throws IOException
	 */
	public static void writeSubHeading(Writer out, String heading) throws IOException {
		out.write("<h2>" + escape(heading) + "</h2>");
		out.flush();
	}

	/**
	 * Write a string surrounded by p tags.
	 * @param out Writer to write to.
	 * @param text the text to write.
	 * @throws IOException
	 */
	public static void writeParagraph(Writer out, String text) throws IOException {
		out.write("<p>" + escape(text) + "</p>");
		out.flush();
	}

	/**
	 * Write a string surrounded by pre tags.
	 * @param out Writer to write to.
	 * @param text the text to write.
	 * @throws IOException
	 */
	public static void writePre(Writer out, String text) throws IOException {
		out.write("<pre>" + escape(text) + "</pre>");
		out.flush();
	}

	/**
	 * Write a string that is a command being executed.
	 * @param out Writer to write to.
	 * @param text the text to write.
	 * @throws IOException
	 */
	public static void writeCommand(Writer out, String command) throws IOException {
		out.write("<pre class=\"command\">" + escape(command) + "</pre>");
		out.flush();
	}

	/**
	 * Write a warning.
	 * @param out Writer to write to.
	 * @param warning a warning message.
	 * @throws IOException
	 */
	public static void writeWarning(Writer out, String warning) throws IOException {
		out.write("<p class=\"warning\">Warning: " + escape(warning) + "</p>");
		out.flush();
	}

	/**
	 * Write a string that is a command being executed.
	 * @param out Writer to write to.
	 * @param text the text to write.
	 * @throws IOException
	 */
	public static void writeLink(Writer out, String url, String text) throws IOException {
		out.write("<p><a href=\"" + escape(url) + "\">" + escape(text) + "</a></p>");
	}

	/**
	 * Output a map as a two-column table.
	 * @param out Writer to write to.
	 * @param values The values to write.
	 * @throws IOException
	 */
	public static void writeMapAsTable(Writer out, Map<String, String> values) throws IOException {
		out.write("<table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody>");
		boolean even = false;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			out.write("<tr class=\"" + (even ? "even" : "odd") + "\"><td>" + escape(entry.getKey()) + ":</td><td>" +
					escape(entry.getValue()) + "</td></tr>");
			even = !even;
		}
		out.write("</tbody></table>");
	}

	public static void writeFormStart(Writer out) throws IOException {
		out.write("<form method=\"post\" action=\"MaximaPool\">");
	}

	/**
	 * Output a textarea.
	 * @param out Writer to write to.
	 * @param label form element label.
	 * @param name the textarea name.
	 * @param initialContent any content to display in the textarea initially.
	 * @throws IOException
	 */
	public static void writeTextarea(Writer out, String label, String name, String initialContent) throws IOException {
		name = escape(name);
		out.write("<p><label for=\"" + name + "\">" + escape(label) + "</label><br>" +
				"<textarea rows=\"5\" cols=\"80\" id=\"" + name + "\" name=\"" + escape(name) + "\">" +
				escape(initialContent) + "</textarea></p>");
	}

	public static void writeSelect(Writer out, String label, String name, String[][] options) throws IOException {
		out.write("<p><label for=\"" + name + "\">" + escape(label) + "</label>" +
				"<select id=\"" + name + "\" name=\"" + name + "\">");
		for (String[] option : options) {
			out.write("<option value=\"" + escape(option[0]) + "\">" +
						escape(option[1]) + "</option>");
		}
		out.write("</select></p>");
	}

	public static void writeFormFinish(Writer out, String buttonLabel) throws IOException {
		out.write("<p><input type=\"submit\" value=\"" + escape(buttonLabel) + "\"></p></form>");
	}

	/**
	 * Output a text input.
	 * @param out Writer to write to.
	 * @param label form element label.
	 * @param name the textarea name.
	 * @param initialContent any content to display in the textarea initially.
	 * @throws IOException
	 */
	public static void writeActionButton(Writer out, String action, String value, String buttonLabel) throws IOException {
		String idprefix = (action + value).replaceAll("[^a-zA-Z0-9]", "");
		out.write("<form method=\"post\" action=\"MaximaPool\">");
		out.write("<p><label for=\"" + idprefix + "password\">Password</label>");
		out.write("<input type=\"text\" id=\"" + idprefix + "password\" name=\"password\">");
		out.write("<input type=\"hidden\" name=\"" + escape(action) +
							"\" value=\"" + escape(value) + "\">");
		out.write("<input type=\"submit\" value=\"" + escape(buttonLabel) + "\"></p></form>");
	}

	/**
	 * Output information about an exception.
	 * @param out Writer to write to.
	 * @param e the Exception to display.
	 * @param message Custom message to display.
	 * @throws IOException
	 */
	public static void writeException(PrintWriter out, Exception e, String message) throws IOException {
		writeParagraph(out, message);
		out.write("<pre>");
		e.printStackTrace(out);
		out.write("</pre>");
	}

	/**
	 * Helper method used by the healthcheck.
	 * @param out Writer to write to.
	 * @param output the process output to stream through to out.
	 * @param test test string to look out for. We stop once we see this in the output.
	 * @param previousOutput output so far. Not re-displayed.
	 * @param timeout time at which to abort. Compared to System.currentTimeMillis().
	 * @return all the output so far. Can be used as previousOutput for future calls.
	 * @throws IOException
	 */
	public static String streamOutputUntil(PrintWriter out, ReaderSucker output, String test,
			String previousOutput, long timeout) throws IOException {

		writeParagraph(out, "Waiting for target text: '" + test + "'");

		while (true) {
			try {
				Thread.sleep(0, 200);
			} catch (InterruptedException e) {
				writeException(out, e, "Exception while waiting for output.");
			}

			if (System.currentTimeMillis() > timeout) {
				writeParagraph(out, "Timeout!");
				throw new RuntimeException("Timeout");
			}

			String currentOutput = output.currentValue();

			if (!currentOutput.equals(previousOutput)) {
				writePre(out, currentOutput.substring(previousOutput.length()));
				previousOutput = currentOutput;
			}

			if (previousOutput.indexOf(test) >= 0) {
				break;
			}
		}

		return previousOutput;
	}

	/**
	 * Send a response giving the error when an exception occurs.
	 * @param response the response to send the HTML head to.
	 * @return a so you can continue the output.
	 * @throws IOException
	 */
	public static void sendErrorPage(HttpServletResponse response, Exception e) throws IOException {
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
				"<p>" + e.getMessage() + "</p><pre>" + sw.toString() + "</pre>");
	}
}
