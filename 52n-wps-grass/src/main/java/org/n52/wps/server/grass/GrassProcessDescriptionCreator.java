/***************************************************************
Copyright � 2009 52�North Initiative for Geospatial Open Source Software GmbH

 Author: Benjamin Pro�, 52�North

 Contact: Andreas Wytzisk, 
 52�North Initiative for Geospatial Open Source SoftwareGmbH, 
 Martin-Luther-King-Weg 24,
 48155 Muenster, Germany, 
 info@52north.org

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 version 2 as published by the Free Software Foundation.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; even without the implied WARRANTY OF
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program (see gnu-gpl v2.txt). If not, write to
 the Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 Boston, MA 02111-1307, USA or visit the Free
 Software Foundation�s web page, http://www.fsf.org.

 ***************************************************************/
package org.n52.wps.server.grass;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.opengis.wps.x100.InputDescriptionType;
import net.opengis.wps.x100.ProcessDescriptionType;
import net.opengis.wps.x100.ProcessDescriptionsDocument;
import net.opengis.wps.x100.SupportedComplexDataInputType;
import net.opengis.wps.x100.SupportedComplexDataType;

import org.apache.log4j.Logger;
import org.apache.xmlbeans.XmlException;
import org.n52.wps.io.IOHandler;
import org.n52.wps.server.grass.io.GrassIOHandler;
import org.n52.wps.server.grass.util.StreamGobbler;

public class GrassProcessDescriptionCreator {
	
	private final String fileSeparator = System.getProperty("file.separator");
	private final String lineSeparator = System.getProperty("line.separator");
	private String grassHome = "c:\\programme\\grass-70-svn";
	private String pythonHome = "c:\\programme\\grass-70-svn";
	private String projShareDir = "c:\\programme\\grass-70-svn";

	private String[] envp = null;
	
	private static Logger LOGGER = Logger.getLogger(GrassProcessDescriptionCreator.class);
	private final String wpsProcessDescCmd = " --wps-process-description";
	private Runtime rt = Runtime.getRuntime();
	private ExecutorService executor = Executors.newFixedThreadPool(10);
	private String gisrcDir;

	public GrassProcessDescriptionCreator() {

		grassHome = GrassProcessRepository.grassHome;
		pythonHome = GrassProcessRepository.pythonHome;
		gisrcDir = GrassProcessRepository.gisrcDir;
	}

	public ProcessDescriptionType createDescribeProcessType(String identifier)
			throws IOException, XmlException {

		Process proc = null;

		if (!GrassIOHandler.OS_Name.startsWith("Windows")) {
			proc = rt.exec(grassHome + fileSeparator + "bin" + fileSeparator
					+ identifier + wpsProcessDescCmd, getEnvp());
		} else {
			proc = rt.exec(grassHome + fileSeparator + "bin" + fileSeparator
					+ identifier + ".exe" + wpsProcessDescCmd, getEnvp());
		}

		PipedOutputStream pipedOut = new PipedOutputStream();

		PipedInputStream pipedIn = new PipedInputStream(pipedOut);

		PipedOutputStream pipedOutError = new PipedOutputStream();

		PipedInputStream pipedInError = new PipedInputStream(pipedOutError);
		
		// any error message?
		StreamGobbler errorGobbler = new StreamGobbler(proc.getErrorStream(),
				"ERROR", pipedOutError);

		// any output?
		StreamGobbler outputGobbler = new StreamGobbler(proc.getInputStream(),
				"OUTPUT", pipedOut);

		executor.execute(errorGobbler);
		executor.execute(outputGobbler);

		BufferedReader xmlReader = new BufferedReader(new InputStreamReader(
				pipedIn));

		String line = xmlReader.readLine();

		String xml = "";

		while (line != null) {

			xml = xml.concat(line + lineSeparator);

			line = xmlReader.readLine();
		}

		pipedIn.close();
		pipedOut.close();
		xmlReader.close();

		BufferedReader errorReader = new BufferedReader(new InputStreamReader(
				pipedInError));

		String errorLine = errorReader.readLine();

		String errors = "";

		while (line != null) {

			errors = xml.concat(errorLine + lineSeparator);

			errorLine = errorReader.readLine();
		}

		if (errors != "") {
			LOGGER.error("Error while creating processdescription for process "
					+ identifier + ": " + errors);
		}
		
		pipedInError.close();
		pipedOutError.close();
		errorReader.close();
		
		try {
			proc.waitFor();
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}

		proc.destroy();

		ProcessDescriptionsDocument pDoc = ProcessDescriptionsDocument.Factory
				.parse(xml);

		int i = pDoc.getProcessDescriptions().getProcessDescriptionArray().length;

		if (i == 1) {

			ProcessDescriptionType result = pDoc.getProcessDescriptions()
					.getProcessDescriptionArray()[0];

			SupportedComplexDataType outputType = result.getProcessOutputs()
					.getOutputArray(0).getComplexOutput();

			if (outputType != null) {

				String schema = outputType.getDefault().getFormat().getSchema();

				if (schema != null) {

					if (schema
							.contains("http://schemas.opengis.net/gml/2.0.0/feature.xsd")
							|| schema
									.contains("http://schemas.opengis.net/gml/2.1.1/feature.xsd")
							|| schema
									.contains("http://schemas.opengis.net/gml/2.1.2/feature.xsd")
							|| schema
									.contains("http://schemas.opengis.net/gml/2.1.2.1/feature.xsd")
							|| schema
									.contains("http://schemas.opengis.net/gml/3.0.0/base/feature.xsd")
							|| schema
									.contains("http://schemas.opengis.net/gml/3.0.1/base/feature.xsd")
							|| schema
									.contains("http://schemas.opengis.net/gml/3.1.1/base/feature.xsd")) {

						outputType.getSupported().addNewFormat()
								.setMimeType(IOHandler.MIME_TYPE_ZIPPED_SHP);

					}
				}

			}

			return result;
		}

		return null;
	}

	/**
	 * @param args
	 * @throws IOException
	 * @throws XmlException
	 */
	public static void main(String[] args) throws IOException {

		ProcessDescriptionType type;
		try {
			type = new GrassProcessDescriptionCreator()
					.createDescribeProcessType("v.buffer");

			for (int i = 0; i < type.getDataInputs().getInputArray().length; i++) {

				InputDescriptionType inputDescType = type.getDataInputs()
						.getInputArray()[i];

				SupportedComplexDataInputType supCDataType = inputDescType
						.getComplexData();

				System.out.println(supCDataType);
			}
		} catch (XmlException e) {
			e.printStackTrace();
		}

	}

	private String[] getEnvp() {

		if (envp == null) {

			envp = new String[] {
					"GISRC=" + gisrcDir,
					"GDAL_DATA=" + grassHome + fileSeparator + "etc"
							+ fileSeparator + "ogr_csv",
					"GISBASE=" + grassHome,
					"PATH=" + grassHome + fileSeparator + "lib;" + grassHome
							+ fileSeparator + "bin;" + grassHome
							+ fileSeparator + "scripts;" + pythonHome + ";"
							+ grassHome + fileSeparator + "extralib;"
							+ grassHome + fileSeparator + "extrabin",
					"LD_LIBRARY_PATH=" + grassHome + fileSeparator + "lib",
					"PWD=" + grassHome,
					"PYTHONHOME=" + pythonHome,
					"PYTHONPATH=" + grassHome + fileSeparator + "etc"
							+ fileSeparator + "python",
					"GRASS_CONFIG_DIR=.grass7",
					"GRASS_GNUPLOT=gnuplot -persist", 
					"GRASS_PAGER=less",
					"GRASS_PYTHON=python",
					"GRASS_SH=/bin/sh", "GRASS_VERSION=7.0.svn",
					"WINGISBASE=" + grassHome };
		}
		return envp;
	}

}