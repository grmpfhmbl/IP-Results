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
import org.n52.wps.algorithm.annotation.*;
import org.n52.wps.commons.context.ExecutionContextFactory;
import org.n52.wps.io.data.GenericFileData;
import org.n52.wps.io.data.GenericFileDataConstants;
import org.n52.wps.io.data.binding.complex.GenericFileDataBinding;
import org.n52.wps.server.AbstractAnnotatedAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Runs SWAT model.
 */
@Algorithm(
        version = "0.0.1", //TODO get this value from maven pom (via properties file)
        abstrakt = "This Algorithm runs SWAT on a given input model and returns the SWAT output in a ZIP file.",
        title = "SWAT Runner Algoritm",
        identifier = "swat-runner-algorithm",
        statusSupported = false,
        storeSupported = false)
public class AnnotatedSwatRunnerAlgorithm extends AbstractAnnotatedAlgorithm {

    private static final Logger logger = LoggerFactory.getLogger(AnnotatedSwatRunnerAlgorithm.class);

    private List<GenericFileData> swatInputZip;
    private String swatConsoleOutput = "";
    private GenericFileData swatOutputZipped;

    @ComplexDataInput(
            identifier = "swat_model",
            title = "swat model input files",
            abstrakt = "ZIP file containing the SWAT input files.",
            binding = GenericFileDataBinding.class,
            minOccurs = 1, maxOccurs = 1)
    public void setSwatInputZip(List<GenericFileData> gfd) {
        this.swatInputZip = gfd;
    }

    @ComplexDataOutput(identifier = "swat_output_zipped",
            title = "swat model output files as ZIP",
            abstrakt = "ZIP file containing the SWAT output files.",
            binding = GenericFileDataBinding.class)
    public GenericFileData getSwatOutputZipped() {
        return this.swatOutputZipped;
    }

    @LiteralDataOutput(identifier = "swat_console_output",
            title = "SWAT model console output",
            abstrakt = "The stdout of the SWAT model run.")
    public String getSwatConsoleOutput() {
        return this.swatConsoleOutput;
    }

    @Execute
    public void runSwatProcess() throws IOException {
        logger.info("Trying to run SWAT model");

        //TODO make a list of needed directories and create in loop
        String tempDirStr = ExecutionContextFactory.getContext().getTempDirectoryPath();
        File tempDir = new File(tempDirStr + System.getProperty("file.separator"));
        File swatModelDir = new File(tempDirStr + System.getProperty("file.separator") +
                                             "swatmodel" + System.getProperty("file.separator"));

        logger.info("Temp dir is: " + tempDirStr);
        logger.info("Temp file is: " + tempDir.getAbsolutePath());

        try {
            if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
                throw new IOException("Could not create temp dir " + tempDir);
            }
            if (!swatModelDir.isDirectory() && !swatModelDir.mkdirs()) {
                throw new IOException("Could not create swatmodel dir " + tempDir);
            }

            //unpack swat model
            if (swatInputZip == null) {
                logger.info("SwatInputZip was NULL");
            }
            else if (swatInputZip.size() != 1) {
                logger.info("SwatInputZip size != 1 - " + swatInputZip.size());
            }
            else {
                logger.info("Unpacking swatInputZip " + swatInputZip.get(0).getBaseFile(false).getAbsolutePath()
                                    + " to " + swatModelDir.getAbsolutePath());
                net.lingala.zip4j.core.ZipFile zipFile = new net.lingala.zip4j.core.ZipFile(swatInputZip.get(0).getBaseFile(false));
                zipFile.extractAll(swatModelDir.getAbsolutePath());
            }

            URI jarUri = this.getJarURI();
            logger.debug("Jar-File URI " + jarUri);

            //FIXME this is bullshit, make own jar for every OS and provide executable this way.
            String exeFilename = "swat/swat_rel64";
            if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
                exeFilename = exeFilename.concat("_win.exe");
            }
            else if (System.getProperty("os.name").toLowerCase().startsWith("mac")) {
                exeFilename = exeFilename.concat("_osx");
            }
            else if (System.getProperty("os.name").toLowerCase().startsWith("linux")) {
                exeFilename = exeFilename.concat("_linux");
            }
            else {
                logger.warn("Could not determine OS, trying generic executable name");
            }

            URI exeFile = getFile(jarUri, exeFilename);
            new File(exeFile).setExecutable(true);

            ProcessBuilder pb = new ProcessBuilder(new File(exeFile).toString());
            pb.redirectErrorStream(true);
            pb.directory(swatModelDir);
            Process process = pb.start();
            InputStream stdOutStream = process.getInputStream();
            InputStreamReader isr = new InputStreamReader(stdOutStream);
            BufferedReader br = new BufferedReader(isr);
            String line;
            logger.info(String.format("Output of running %s is:\n",
                                      Arrays.toString(pb.command().toArray())
                                     ));
            while ((line = br.readLine()) != null) {
                logger.info(line);
                this.swatConsoleOutput = this.swatConsoleOutput.concat(line).concat("\n");
            }

            int exitValue = process.waitFor();
            if (exitValue != 0) {
                throw new IOException("SWAT didn't complete successfully");
            }

            Collection<File> outFiles = FileUtils.listFiles(swatModelDir,
                                                            new WildcardFileFilter("output.*"), TrueFileFilter.TRUE
                                                           );
            File outFilesZippend = org.n52.wps.io.IOUtils.zip(outFiles.toArray(new File[outFiles.size()]));
            this.swatOutputZipped = new GenericFileData(outFilesZippend, "application/zip");
        } catch (URISyntaxException e) {
            logger.error("Could not determine uri of jar. ", e);
            throw new IOException("Could not determine uri of jar. ", e);
        } catch (InterruptedException e) {
            logger.error("Exception on running SWAT process.", e);
            throw new IOException("Exception on running SWAT process.", e);
        } catch (net.lingala.zip4j.exception.ZipException e) {
            logger.error("Could not extract swat input model.", e);
            throw new IOException("Could not extract swat input model.", e);
        } finally {
            //TODO FIXME is that really necessary? The Execution context should delete this?
/*
            if (tempDir.isDirectory()) {
                FileUtils.deleteDirectory(tempDir);
            }
*/
        }
    }

    /**
     * Gets the URI of the jar file this class is in.
     *
     * @return URI of jar file this class is in
     * @throws URISyntaxException
     */
    private URI getJarURI()
            throws URISyntaxException {
        final ProtectionDomain domain;
        final CodeSource source;
        final URL url;
        final URI uri;

        domain = this.getClass().getProtectionDomain();
        source = domain.getCodeSource();
        url = source.getLocation();
        uri = url.toURI();

        return (uri);
    }

    /**
     * @param locationUri
     * @param fileName
     * @return
     * @throws ZipException
     * @throws IOException
     */
    private URI getFile(final URI locationUri, final String fileName)
            throws ZipException, IOException {
        final File location;
        final URI fileURI;

        location = new File(locationUri);

        // not in a JAR, just return the path on disk
        if (location.isDirectory()) {
            fileURI = URI.create(locationUri.toString() + System.getProperty("file.separator") + fileName);
        }
        else {
            final ZipFile zipFile;

            zipFile = new ZipFile(location);

            try {
                fileURI = extractToTemp(zipFile, fileName);
            } finally {
                zipFile.close();
            }
        }
        return (fileURI);
    }

    private URI extractToTemp(final ZipFile zipFile, final String filename)
            throws IOException {
        final File tempFile;
        final ZipEntry entry;


        tempFile = new File(ExecutionContextFactory.getContext().getTempDirectoryPath() + System
                .getProperty("file.separator") + filename);
        tempFile.getParentFile().mkdirs();
        entry = zipFile.getEntry(filename);

        if (entry == null) {
            throw new FileNotFoundException("cannot find file: " + filename + " in archive: " + zipFile.getName());
        }

        try (OutputStream fileStream = new FileOutputStream(tempFile);
             InputStream zipStream = zipFile.getInputStream(entry)) {
            final byte[] buf;
            int i;

            buf = new byte[1024];
            i = 0;

            while ((i = zipStream.read(buf)) != -1) {
                fileStream.write(buf, 0, i);
            }
        }

        return (tempFile.toURI());
    }
}
