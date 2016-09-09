/**
 * Copyright (C) 2016 Z_GIS (http://www.zgis.at)
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zgis.wps.swat;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.n52.wps.algorithm.annotation.*;
import org.n52.wps.commons.context.ExecutionContextFactory;
import org.n52.wps.io.data.GenericFileData;
import org.n52.wps.io.data.binding.complex.GenericFileDataBinding;
import org.n52.wps.server.AbstractAnnotatedAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Runs SWAT model.
 */
@Algorithm(
        version = "0.0.1", //TODO get this value from maven pom (via properties file)
        abstrakt = "This Algorithm fethers GSOD weather from SOS and puts it in .",
        title = "GSOD weather fetcher",
        identifier = "swat-weather-fetch-algorithm",
        statusSupported = false,
        storeSupported = false)
public class WeatherFetchAlgorithm extends AbstractAnnotatedAlgorithm {

    private static final Logger logger = LoggerFactory.getLogger(WeatherFetchAlgorithm.class);

    private GenericFileData weatherZipped;
    private String sosUrl;
    private String sosProcedure;

    @LiteralDataInput(
            identifier = "sos_url",
            title = "SOS url",
            abstrakt = "SOS url containing weather data",
            minOccurs = 1,
            maxOccurs = 1
    )
    public void setSosUrl(String sosUrl) {
        this.sosUrl = sosUrl;
    }

    @LiteralDataInput(
            identifier = "sos_procedure",
            title = "SOS procedure",
            abstrakt = "SOS procedure containing weather data",
            maxOccurs = 1,
            defaultValue = "http://vocab.example.com/sensorweb/procedure/gsod"
    )
    public void setSosProcedure(String sosProcedure) {
        this.sosProcedure = sosProcedure;
    }

    @ComplexDataOutput(identifier = "weather_zip",
            title = "Fetches weather as ZIP",
            abstrakt = "ZIP file containing the SWAT output files.",
            binding = GenericFileDataBinding.class)
    public GenericFileData getWeatherZipped() {
        return this.weatherZipped;
    }

    @Execute
    public void run() throws IOException {
        logger.info("Fetching Weather");

        //TODO make a list of needed directories and create in loop
        String tempDirStr = ExecutionContextFactory.getContext().getTempDirectoryPath();
        File tempDir = new File(tempDirStr + System.getProperty("file.separator"));

        logger.info("Temp dir is: " + tempDirStr);
        logger.info("Temp file is: " + tempDir.getAbsolutePath());

        try {
            if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
                throw new IOException("Could not create temp dir " + tempDir);
            }

            //TODO fetch SOS
            String response = getObservations(this.sosUrl, sosProcedure, 3);

            //FIXME set file filter!
            Collection<File> outFiles = FileUtils.listFiles(tempDir,
                                                            new WildcardFileFilter("output.*"), TrueFileFilter.TRUE
                                                           );
            File outFilesZippend = org.n52.wps.io.IOUtils.zip(outFiles.toArray(new File[outFiles.size()]));
            this.weatherZipped = new GenericFileData(outFilesZippend, "application/zip");
        } finally {
            //TODO FIXME is that really necessary? The Execution context should delete this?
/*
            if (tempDir.isDirectory()) {
                FileUtils.deleteDirectory(tempDir);
            }
*/
        }
    }

    private static String getObservations(String sosUrl, String sensor, String observedProperty,
                                          int years) throws IOException {
        String result = null;
        String offering = null; // "SOE_GWL_MASL";
        String procedure = sensor;
        // String observedProperty =
        // "http://vocab.smart-project.info/sensorweb/phenomenon/Humidity";
        String responseFormat = "http://www.opengis.net/om/2.0"; // "http://www.opengis.net/waterml/2.0";
        // String temporalFilter =
        // "om:phenomenonTime,2014-02-17T12:00:00/2014-02-18T20:00:00";

        DateTimeFormatter fmt = DateTimeFormat
                .forPattern(" yyyy-MM-dd'T'HH:mm:ss");
        DateTimeFormatter fmtTz = DateTimeFormat
                .forPattern(" yyyy-MM-dd'T'HH:mm:ssZZ");
        DateTimeFormatter fmtIso = ISODateTimeFormat.dateTime();

        DateTime now = DateTime.parse("2016-01-01 00:00:00");
        DateTime minusYears = now.minusYears(years);

        String temporalFilter = "om:phenomenonTime,"
                + minusYears.toString(fmtIso) + "/" + now.toString(fmtIso);

        StringBuilder resultList = new StringBuilder();
        StringBuilder kvpRequestParams = new StringBuilder();
        kvpRequestParams.append("?service=" + "SOS");
        kvpRequestParams.append("&version=" + "2.0.0");
        kvpRequestParams.append("&request=" + "GetObservation");

        if (procedure != null && !procedure.isEmpty()) {
            kvpRequestParams.append("&procedure="
                                            + URLEncoder.encode(procedure, "UTF-8"));
        }

        if (observedProperty != null && !observedProperty.isEmpty()) {
         kvpRequestParams.append("&observedProperty="
         + URLEncoder.encode(observedProperty, "UTF-8"));
         }

        if (responseFormat != null && !responseFormat.isEmpty()) {
            kvpRequestParams.append("&responseFormat="
                                            + URLEncoder.encode(responseFormat, "UTF-8"));
        }
        // om:phenomenonTime,2010-01-01T12:00:00/2011-07-01T14:00:00
        if (temporalFilter != null && !temporalFilter.isEmpty()) {
            kvpRequestParams.append("&temporalFilter="
                                            + URLEncoder.encode(temporalFilter, "UTF-8"));
        }

        // set the connection timeout value to xx milliseconds
        final HttpParams httpParams = new BasicHttpParams();
        HttpClient httpclient = new DefaultHttpClient(httpParams);
        HttpGet httpget = new HttpGet(sosUrl + kvpRequestParams.toString());

        HttpResponse response;
        HttpEntity entity;

        response = httpclient.execute(httpget);
        HttpEntity resEntity = response.getEntity();
        BufferedReader rd = new BufferedReader(new InputStreamReader(
                resEntity.getContent()));
        String line;
        while ((line = rd.readLine()) != null) {
            resultList.append(line);
        }
        final String responseBody = resultList.toString();
        OM2MeasParse read = new OM2MeasParse();
        List<ObservationModel> tvps = read.readData(responseBody);

        httpclient.getConnectionManager().shutdown();

        Collections.sort(tvps, new Comparator<ObservationModel>() {
            public int compare(ObservationModel m1, ObservationModel m2) {
                return m1.getDatetime().compareTo(m2.getDatetime());
            }
        });

        if (tvps.size() > 0) {
            return "OK - [" + sensor + "] last: "
                    + tvps.get(tvps.size() - 1).getDate();
        } else {
            return "MISSING";
        }
    }
}
