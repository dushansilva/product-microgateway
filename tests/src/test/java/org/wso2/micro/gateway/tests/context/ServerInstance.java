/*
 * Copyright (c) WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.micro.gateway.tests.context;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.mina.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.micro.gateway.tests.util.TestConstant;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


/**
 * This class hold the server information and manage the a server instance.
 */
public class ServerInstance implements Server {
    private static final Logger log = LoggerFactory.getLogger(ServerInstance.class);
    private String serverHome;
    private String serverDistribution;
    private String[] args;
    private Process process;
    private ServerLogReader serverInfoLogReader;
    private ServerLogReader serverErrorLogReader;
    private boolean isServerRunning;
    private int httpServerPort = TestConstant.GATEWAY_LISTENER_HTTP_PORT;
    private int httpsServerPort = TestConstant.GATEWAY_LISTENER_HTTPS_PORT;
    private int httpServerPortToken = TestConstant.GATEWAY_LISTENER_HTTPS_TOKEN_PORT;
    private ConcurrentHashSet<LogLeecher> tmpLeechers = new ConcurrentHashSet<>();

    /**
     * The parent directory which the micro-gw runtime will be extracted to.
     */
    private String extractDir;

    public ServerInstance(String serverDistributionPath) throws MicroGWTestException {
        this.serverDistribution = serverDistributionPath;
        initialize();
    }

    public ServerInstance(String serverDistributionPath, int serverHttpPort, int serverHttpsPort,
            int serverHttpsTokenPort) throws MicroGWTestException {
        this.serverDistribution = serverDistributionPath;
        this.httpServerPort = serverHttpPort;
        this.httpsServerPort = serverHttpsPort;
        this.httpServerPortToken = serverHttpsTokenPort;
        initialize();
    }

    /**
     * Method to start Micro-GW server given the port and bal file.
     *
     * @param httpPort       http server port
     * @param httpsPort      https server port
     * @param tokenHttpsPost https server token endpoint port
     * @return microGWServer      Started server instance.
     * @throws MicroGWTestException
     */
    public static ServerInstance initMicroGwServer(int httpPort, int httpsPort, int tokenHttpsPost)
            throws MicroGWTestException {
        String serverZipPath = System.getProperty(Constants.SYSTEM_PROP_SERVER_ZIP);
        ServerInstance microGWServer = new ServerInstance(serverZipPath, httpPort, httpsPort, tokenHttpsPost);
        return microGWServer;
    }

    /**
     * Method to start Micro-GW server in default port 9092 with given bal file.
     *
     * @return microGWServer      Started server instance.
     * @throws MicroGWTestException
     */
    public static ServerInstance initMicroGwServer() throws MicroGWTestException {
        return initMicroGwServer(TestConstant.GATEWAY_LISTENER_HTTP_PORT, TestConstant.GATEWAY_LISTENER_HTTPS_PORT,
                TestConstant.GATEWAY_LISTENER_HTTPS_TOKEN_PORT);
    }

    public void startMicroGwServer(String balFile) throws MicroGWTestException {
        String[] args = {balFile, "-e", "b7a.log.level=DEBUG"};
        setArguments(args);

        startServer();
    }

    public void startMicroGwServer(String balFile, String[] args) throws MicroGWTestException {
        String[] newArgs = {balFile};
        newArgs = ArrayUtils.addAll(args, newArgs);
        setArguments(newArgs);

        startServer();
    }

    /**
     * Start the server pointing to the ballerina.conf path.
     *
     * @param balFile ballerina file path
     * @param gwConfPath ballerina.conf file path
     * @throws MicroGWTestException if an error occurs while starting the server
     */
    public void startMicroGwServerWithConfigPath(String balFile, String gwConfPath) throws
            MicroGWTestException {
        String gwConfigPathArg = "--config ";
        String[] args = {gwConfigPathArg, gwConfPath, balFile};
        setArguments(args);

        startServer();
    }

    /**
     * Start a server instance y extracting a server zip distribution.
     *
     * @throws MicroGWTestException if server start fails
     */
    @Override
    public void startServer() throws MicroGWTestException {

        if (args == null | args.length == 0) {
            throw new IllegalArgumentException("No Argument provided for server startup.");
        }

        Utils.checkPortAvailability(httpServerPort);
        Utils.checkPortAvailability(httpsServerPort);
        Utils.checkPortAvailability(httpServerPortToken);

        log.info("Starting server..");

        startServer(args);

        serverInfoLogReader = new ServerLogReader("inputStream", process.getInputStream());
        tmpLeechers.forEach(leacher -> serverInfoLogReader.addLeecher(leacher));
        serverInfoLogReader.start();
        serverErrorLogReader = new ServerLogReader("errorStream", process.getErrorStream());
        serverErrorLogReader.start();
        log.info("Waiting for port " + httpServerPort + " to open");
        Utils.waitForPort(httpServerPort, 1000 * 60 * 2, false, "localhost");
        log.info("Server Started Successfully.");
        isServerRunning = true;
    }

    /**
     * Initialize the server instance with properties.
     * @throws MicroGWTestException when an exception is thrown while initializing the server
     */
    private void initialize() throws MicroGWTestException {
        if (serverHome == null) {
            setUpServerHome(serverDistribution);
            log.info("Server Home " + serverHome);
            configServer();
        }
    }

    /**
     * Stop the server instance which is started by start method.
     *
     * @throws MicroGWTestException if service stop fails
     */
    @Override
    public void stopServer(boolean deleteExtractedDir) throws MicroGWTestException {
        log.info("Stopping server..");
        if (process != null) {
            String pid;
            try {
                pid = getServerPID();
                if (Utils.getOSName().toLowerCase().contains("windows")) {
                    Process killServer = Runtime.getRuntime().exec("TASKKILL -F /PID " + pid);
                    log.info(readProcessInputStream(killServer.getInputStream()));
                    killServer.waitFor(15, TimeUnit.SECONDS);
                    killServer.destroy();
                } else {
                    Process killServer = Runtime.getRuntime().exec("kill -9 " + pid);
                    killServer.waitFor(15, TimeUnit.SECONDS);
                    killServer.destroy();
                }
            } catch (IOException e) {
                log.error("Error getting process id for the server in port - " + httpServerPort
                        + " error - " + e.getMessage(), e);
                throw new MicroGWTestException("Error while getting the server process id", e);
            } catch (InterruptedException e) {
                log.error("Error stopping the server in port - " + httpServerPort + " error - " + e.getMessage(), e);
                throw new MicroGWTestException("Error waiting for services to stop", e);
            }
            process.destroy();
            serverInfoLogReader.stop();
            serverErrorLogReader.stop();
            process = null;
            //wait until port to close
            Utils.waitForPortToClosed(httpServerPort, 30000);
            log.info("Server Stopped Successfully");
            if(deleteExtractedDir) {
                deleteWorkDir();
            }
        }
    }

    /**
     * Restart the server instance.
     *
     * @throws MicroGWTestException if the services could not be started
     */
    @Override
    public void restartServer() throws MicroGWTestException {
        log.info("Restarting Server...");
        stopServer(true);
        startServer();
        log.info("Server Restarted Successfully");
    }

    /**
     * Run main with args.
     *
     * @param args string arguments
     * @throws MicroGWTestException if the main could not be started
     */
    public void runMain(String[] args) throws MicroGWTestException {
        runMain(args, null, "run");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runMain(String[] args, String [] envVariables, String command) throws MicroGWTestException {

        initialize();

        String scriptName = Constants.BALLERINA_SERVER_SCRIPT_NAME;
        String[] cmdArray;
        File commandDir = new File(serverHome);

        Process process;

        try {
            if (Utils.getOSName().toLowerCase().contains("windows")) {
                commandDir = new File(serverHome + File.separator + "bin");
                cmdArray = new String[]{"cmd.exe", "/c", scriptName + ".bat", command};
                String[] cmdArgs = Stream.concat(Arrays.stream(cmdArray), Arrays.stream(args))
                        .toArray(String[]::new);
                process = Runtime.getRuntime().exec(cmdArgs, envVariables, commandDir);

            } else {
                cmdArray = new String[]{"bash", "bin/" + scriptName, command};
                String[] cmdArgs = Stream.concat(Arrays.stream(cmdArray), Arrays.stream(args))
                        .toArray(String[]::new);
                process = Runtime.getRuntime().exec(cmdArgs, envVariables, commandDir);
            }
            serverInfoLogReader = new ServerLogReader("inputStream", process.getInputStream());
            tmpLeechers.forEach(leacher -> serverInfoLogReader.addLeecher(leacher));
            serverInfoLogReader.start();
            serverErrorLogReader = new ServerLogReader("errorStream", process.getErrorStream());
            serverErrorLogReader.start();

            process.waitFor();
            deleteWorkDir();
        } catch (IOException e) {
            throw new MicroGWTestException("Error executing micro-gw", e);
        } catch (InterruptedException e) {
            throw new MicroGWTestException("Error waiting for execution to finish", e);
        }
    }

    /**
     * Checking whether server instance is up and running.
     *
     * @return true if the server is up and running
     */
    @Override
    public boolean isRunning() {
        return isServerRunning;
    }

    /**
     * setting the list of command line argument while server startup.
     *
     * @param args list of service files
     */
    public void setArguments(String[] args) {
        this.args = args;
    }

    /**
     * to change the server configuration if required. This method can be overriding when initialising
     * the object of this class.
     */
    protected void configServer() throws MicroGWTestException {
    }

    /**
     * Return server home path.
     *
     * @return absolute path of the server location
     */
    public String getServerHome() {
        return serverHome;
    }

    /**
     * Return the service URL.
     *
     * @param servicePath - http url of the given service
     * @return The service URL
     */
    public String getServiceURLHttp(String servicePath) {
        return "http://localhost:" + httpServerPort + "/" + servicePath;
    }

    /**
     * Add a Leecher which is going to listen to an expected text.
     *
     * @param leecher The Leecher instance
     */
    public void addLogLeecher(LogLeecher leecher) {
        if (serverInfoLogReader == null) {
            tmpLeechers.add(leecher);
            return;
        }
        serverInfoLogReader.addLeecher(leecher);
    }

    /**
     * Unzip carbon zip file and return the carbon home. Based on the coverage configuration
     * in automation.xml.
     * This method will inject jacoco agent to the carbon server startup scripts.
     *
     * @param serverZipFile - Carbon zip file, which should be specified in test module pom
     * @throws MicroGWTestException if setting up the server fails
     */
    private void setUpServerHome(String serverZipFile)
            throws MicroGWTestException {
        if (process != null) { // An instance of the server is running
            return;
        }
        int indexOfZip = serverZipFile.lastIndexOf(".zip");
        if (indexOfZip == -1) {
            throw new IllegalArgumentException(serverZipFile + " is not a zip file");
        }
        String fileSeparator = (File.separator.equals("\\")) ? "\\" : "/";
        if (fileSeparator.equals("\\")) {
            serverZipFile = serverZipFile.replace("/", "\\");
        }
        String extractedCarbonDir =
                serverZipFile.substring(serverZipFile.lastIndexOf(fileSeparator) + 1,
                        indexOfZip);
        String baseDir = (System.getProperty(Constants.SYSTEM_PROP_BASE_DIR, ".")) + File.separator + "target";

        extractDir = new File(baseDir).getAbsolutePath() + File.separator + "micro-gwtmp" + System.currentTimeMillis();

        log.info("Extracting micro-gw zip file.. ");

        try {
            Utils.extractFile(serverZipFile, extractDir);
            this.serverHome = extractDir + File.separator + extractedCarbonDir;
            //copies the policies.yaml to run test cases.
            Files.copy(Paths.get(getClass().getClassLoader().getResource(Constants.POLICIES_FILE).getPath()), Paths.get(
                    serverHome + File.separator + Constants.RESOURCES_FOLDER + File.separator
                            + Constants.DEFINITION_FOLDER + File.separator + Constants.POLICIES_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MicroGWTestException("Error extracting server zip file", e);
        }
    }

    /**
     * Executing the sh or bat file to start the server.
     *
     * @param args - command line arguments to pass when executing the sh or bat file
     * @throws MicroGWTestException if starting services failed
     */
    private void startServer(String[] args) throws MicroGWTestException {
        String scriptName = Constants.BALLERINA_SERVER_SCRIPT_NAME;
        String[] cmdArray;
        File commandDir = new File(serverHome+ Constants.BALLERINA_PLATFORM_DIR);

        try {
            if (Utils.getOSName().toLowerCase().contains("windows")) {
                commandDir = new File(serverHome + File.separator + "bin");
                cmdArray = new String[]{"cmd.exe", "/c", scriptName + ".bat", "run"};
                String[] cmdArgs = Stream.concat(Arrays.stream(cmdArray), Arrays.stream(args))
                        .toArray(String[]::new);
                process = Runtime.getRuntime().exec(cmdArgs, null, commandDir);

            } else {
                cmdArray = new String[]{"bash", "bin/" + scriptName, "run"};
                String[] cmdArgs = Stream.concat(Arrays.stream(cmdArray), Arrays.stream(args))
                        .toArray(String[]::new);
                process = Runtime.getRuntime().exec(cmdArgs, null, commandDir);
            }
        } catch (IOException e) {
            throw new MicroGWTestException("Error starting services", e);
        }
    }

    /**
     * reading the server process id.
     *
     * @return process id
     * @throws MicroGWTestException if pid could not be retrieved
     */
    private String getServerPID() throws MicroGWTestException {
        String pid = null;
        if (Utils.getOSName().toLowerCase().contains("windows")) {
            //reading the process id from netstat
            Process tmp;
            try {
                tmp = Runtime.getRuntime().exec("netstat -a -n -o");
            } catch (IOException e) {
                throw new MicroGWTestException("Error retrieving netstat data", e);
            }

            String outPut = readProcessInputStream(tmp.getInputStream());
            String[] lines = outPut.split("\r\n");
            for (String line : lines) {
                String[] column = line.trim().split("\\s+");
                if (column.length < 5) {
                    continue;
                }
                if (column[1].contains(":" + httpServerPort) && column[3].contains("LISTENING")) {
                    log.info(line);
                    pid = column[4];
                    break;
                }
            }
            tmp.destroy();
        } else {

            //reading the process id from ss
            Process tmp = null;
            try {
                String[] cmd = { "bash", "-c",
                        "ss -ltnp \'sport = :" + httpServerPort + "\' | grep LISTEN | awk \'{print $6}\'" };
                tmp = Runtime.getRuntime().exec(cmd);
                String outPut = readProcessInputStream(tmp.getInputStream());
                log.info("Output of the PID extraction command : " + outPut);
                /* The output of ss command is "users:(("java",pid=24522,fd=161))" in latest ss versions
                 But in older versions the output is users:(("java",23165,116))
                 TODO : Improve this OS dependent logic */
                if (outPut.contains("pid=")) {
                    pid = outPut.split("pid=")[1].split(",")[0];
                } else {
                    pid = outPut.split(",")[1];
                }

            } catch (Exception e) {
                log.warn("Error occurred while extracting the PID with ss " + e.getMessage());
                // If ss command fails trying with lsof. MacOS doesn't have ss by default
                pid = getPidWithLsof(httpServerPort);
            } finally {
                if (tmp != null) {
                    tmp.destroy();
                }
            }
        }
        log.info("Server process id in " + Utils.getOSName() + " : " + pid);
        return pid;
    }

    /**
     * Reading output from input stream.
     *
     * @param inputStream input steam of a process
     * @return the output string generated by java process
     */
    private String readProcessInputStream(InputStream inputStream) {
        InputStreamReader inputStreamReader = null;
        BufferedReader bufferedReader = null;
        StringBuilder stringBuilder = new StringBuilder();
        try {
            inputStreamReader = new InputStreamReader(inputStream, Charset.defaultCharset());
            bufferedReader = new BufferedReader(inputStreamReader);
            int x;
            while ((x = bufferedReader.read()) != -1) {
                stringBuilder.append((char) x);
            }
        } catch (Exception ex) {
            log.error("Error reading process id", ex);
        } finally {
            if (inputStreamReader != null) {
                try {
                    inputStream.close();
                    inputStreamReader.close();
                } catch (IOException e) {
                    log.error("Error occurred while closing stream: " + e.getMessage(), e);
                }
            }
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    log.error("Error occurred while closing stream: " + e.getMessage(), e);
                }
            }
        }
        return stringBuilder.toString();
    }

    /**
     * Delete the working directory with the extracted micro-gw instance to cleanup data after execution is complete.
     */
    private void deleteWorkDir() {
        File workDir = new File(extractDir);
        Utils.deleteFolder(workDir);
    }

    /**
     * This method returns the pid of the service which is using the provided port.
     * @param httpServerPort port of the service running
     * @return the pid of the service
     * @throws MicroGWTestException if pid could not be retrieved
     */
    private String getPidWithLsof(int httpServerPort) throws MicroGWTestException {
        String pid;
        Process tmp = null;
        try {
            String[] cmd = { "bash", "-c", "lsof -Pi tcp:" + httpServerPort + " | grep LISTEN | awk \'{print $2}\'" };
            tmp = Runtime.getRuntime().exec(cmd);
            pid = readProcessInputStream(tmp.getInputStream());

        } catch (Exception err) {
            throw new MicroGWTestException("Error retrieving the PID : ", err);
        } finally {
            if (tmp != null) {
                tmp.destroy();
            }
        }
        return pid;
    }
}
