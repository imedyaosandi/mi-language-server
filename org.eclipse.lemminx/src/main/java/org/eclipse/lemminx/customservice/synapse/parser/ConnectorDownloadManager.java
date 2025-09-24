/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     WSO2 LLC - support for WSO2 Micro Integrator Configuration
 */

package org.eclipse.lemminx.customservice.synapse.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.eclipse.lemminx.customservice.synapse.connectors.ConnectorHolder;
import org.eclipse.lemminx.customservice.synapse.connectors.entity.Connector;
import org.eclipse.lemminx.customservice.synapse.driver.DriverGroupIdLookup;
import org.eclipse.lemminx.customservice.synapse.driver.DriverMavenCoordinatesResponse;
import org.eclipse.lemminx.customservice.synapse.mediator.TryOutConstants;
import org.eclipse.lemminx.customservice.synapse.schemagen.json.JSONArray;
import org.eclipse.lemminx.customservice.synapse.schemagen.json.JSONObject;
import org.eclipse.lemminx.customservice.synapse.utils.Constant;
import org.eclipse.lemminx.customservice.synapse.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.eclipse.lemminx.customservice.synapse.utils.Utils.copyFile;
import static org.eclipse.lemminx.customservice.synapse.utils.Utils.getDependencyFromLocalRepo;

public class ConnectorDownloadManager {

    private static final Logger LOGGER = Logger.getLogger(ConnectorDownloadManager.class.getName());

    public static List<String> downloadDependencies(String projectPath, List<DependencyDetails> dependencies) {

        String projectId = new File(projectPath).getName() + "_" + Utils.getHash(projectPath);
        File directory = Path.of(System.getProperty(Constant.USER_HOME), Constant.WSO2_MI, Constant.CONNECTORS,
                projectId).toFile();
        File downloadDirectory = Path.of(directory.getAbsolutePath(), Constant.DOWNLOADED).toFile();
        File extractDirectory = Path.of(directory.getAbsolutePath(), Constant.EXTRACTED).toFile();

        if (!directory.exists()) {
            directory.mkdirs();
        }
        if (!extractDirectory.exists()) {
            extractDirectory.mkdirs();
        }
        if (!downloadDirectory.exists()) {
            downloadDirectory.mkdirs();
        }

        deleteRemovedConnectors(downloadDirectory, dependencies, projectPath);
        List<String> failedDependencies = new ArrayList<>();

        for (DependencyDetails dependency : dependencies) {
            try {
                File connector = Path.of(downloadDirectory.getAbsolutePath(),
                        dependency.getArtifact() + "-" + dependency.getVersion() + Constant.ZIP_EXTENSION).toFile();
                File existingArtifact = null;
                if (connector.exists() && connector.isFile()) {
                    LOGGER.log(Level.INFO, "Dependency already downloaded: " + connector.getName());
                } else if ((existingArtifact = getDependencyFromLocalRepo(dependency.getGroupId(),
                        dependency.getArtifact(), dependency.getVersion(), dependency.getType())) != null) {
                    LOGGER.log(Level.INFO, "Copying dependency from local repository: " + connector.getName());
                    copyFile(existingArtifact.getPath(), downloadDirectory.getPath());
                } else {
                    LOGGER.log(Level.INFO, "Downloading dependency: " + connector.getName());
                    Utils.downloadConnector(dependency.getGroupId(), dependency.getArtifact(), dependency.getVersion(),
                            downloadDirectory, Constant.ZIP_EXTENSION_NO_DOT, projectPath);
                }
            } catch (Exception e) {
                String failedDependency =
                        dependency.getGroupId() + "-" + dependency.getArtifact() + "-" + dependency.getVersion();
                LOGGER.log(Level.WARNING,
                        "Error occurred while downloading dependency " + failedDependency + ": " + e.getMessage());
                failedDependencies.add(failedDependency);
            }
        }
        return failedDependencies;
    }

    private static void deleteRemovedConnectors(File downloadDirectory, List<DependencyDetails> dependencies,
                                                String projectPath) {

        List<String> existingConnectors = dependencies.stream()
                .map(dependency -> dependency.getArtifact() + "-" + dependency.getVersion())
                .collect(Collectors.toList());
        File[] files = downloadDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (isConnectorRemoved(file, existingConnectors)) {
                try {
                    Files.delete(file.toPath());
                    removeFromProjectIfUsingOldCARPlugin(projectPath, file.getName());
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error occurred while deleting removed connector: " + file.getName());
                }
            }
        }
    }

    private static void removeFromProjectIfUsingOldCARPlugin(String projectPath, String name) throws IOException {

        if (!Utils.isOlderCARPlugin(projectPath)) {
            return;
        }
        File connectorInProject = Path.of(projectPath).resolve(TryOutConstants.PROJECT_CONNECTOR_PATH).resolve(name)
                .toFile();
        if (connectorInProject.exists()) {
            Files.delete(connectorInProject.toPath());
        }
    }

    private static boolean isConnectorRemoved(File file, List<String> existingConnectors) {

        return file.isFile() && !existingConnectors.contains(file.getName().replace(Constant.ZIP_EXTENSION, ""));
    }

    /**
     * Downloads the driver JAR for a specific connector and add to local maven repo.
     */
    public static String downloadDriverForConnector(String projectPath, String groupId, String artifactId,
                                                    String version) {

        if (StringUtils.isAnyBlank(groupId, artifactId, version)) {
            LOGGER.log(Level.SEVERE, "Invalid Maven coordinates");
            return null;
        }

        try {
            // 1. Try loading from local Maven repo
            File localDriverFile = getDriverFromLocalRepo(groupId, artifactId, version);
            if (localDriverFile != null) {
                return localDriverFile.getAbsolutePath();
            }

            // 2. Prepare temp directory for download
            String projectId = new File(projectPath).getName() + "_" + Utils.getHash(projectPath);
            File driversDirectory = Path.of(System.getProperty(Constant.USER_HOME), Constant.WSO2_MI,
                    Constant.CONNECTORS, projectId, Constant.DRIVERS).toFile();

            if (!driversDirectory.exists() && !driversDirectory.mkdirs()) {
                LOGGER.log(Level.SEVERE, "Failed to create driver directory: " + driversDirectory.getAbsolutePath());
                return null;
            }

            // 3. Check if driver already exists in temp
            File tempDriverFile = new File(driversDirectory, artifactId + "-" + version + Constant.JAR_EXTENSION);
            if (!tempDriverFile.exists()) {
                // Download only if not present
                LOGGER.log(Level.INFO, "Downloading driver from Maven repository...");
                Utils.downloadConnector(groupId, artifactId, version, driversDirectory, Constant.JAR_EXTENSION_NO_DOT,projectPath);
            } else {
                LOGGER.log(Level.INFO, "Driver already exists in temp: " + tempDriverFile.getAbsolutePath());
            }

            // 4. Validate driver file exists
            if (!tempDriverFile.exists() || !tempDriverFile.isFile()) {
                String msg = "Driver JAR not found after attempted download: " + tempDriverFile.getAbsolutePath();
                LOGGER.log(Level.SEVERE, msg);
                throw new IOException(msg);
            }

            // 5. Add to local repo
            String driverPath = addDriverToLocalRepo(groupId, artifactId, version, tempDriverFile.getAbsolutePath(),
                    projectPath);
            LOGGER.log(Level.INFO, "Driver added to local repo: " + driverPath);
            return driverPath;

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IO error while downloading driver: " + e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error while downloading driver: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * Finds driver info for the specified connection type from the descriptor data
     */
    private static Map<String, Object> findDriverForConnectionType(Map<String, Object> descriptorData,
                                                                   String connectionType) {

        try {
            Object dependenciesObj = descriptorData.get(Constant.DEPENDENCIES);
            if (dependenciesObj != null && dependenciesObj instanceof List) {
                List<Map<String, Object>> dependencies = (List<Map<String, Object>>) dependenciesObj;
                Map<String, Object> exactMatch = null;

                for (Map<String, Object> dependency : dependencies) {
                    Object depConnType = dependency.get(Constant.CONNECTION_TYPE);
                    if (depConnType != null && connectionType.equalsIgnoreCase(depConnType.toString())) {
                        exactMatch = dependency;
                        break;
                    }
                }

                if (exactMatch != null) {
                    return exactMatch;
                }
            }

            LOGGER.log(Level.WARNING, "No driver found for connection type: " + connectionType);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error finding driver for connection type: " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets driver JAR from local Maven repository
     */
    private static File getDriverFromLocalRepo(String groupId, String artifactId, String version) {

        String localMavenRepo = Path.of(System.getProperty(Constant.USER_HOME), Constant.M2, Constant.REPOSITORY)
                .toString();
        String artifactPath = Path.of(localMavenRepo, groupId.replace(".", File.separator), artifactId, version,
                artifactId + "-" + version + Constant.JAR_EXTENSION).toString();
        File artifactFile = new File(artifactPath);

        if (artifactFile.exists()) {
            LOGGER.log(Level.INFO, "Driver found in local repository: " + artifactId);
            return artifactFile;
        } else {
            LOGGER.log(Level.INFO, "Driver not found in local repository: " + artifactId);
            return null;
        }
    }

    /**
     * Add driver JAR to local Maven repository
     */
    public static String addDriverToLocalRepo(String groupId, String artifactId, String version, String filePath,
                                              String projectUri) {

        LOGGER.log(Level.INFO, "Adding driver to local repo.. ");
        boolean isDriverAdded = true;
        //Check if already exists
        File localDriverFile = getDriverFromLocalRepo(groupId, artifactId, version);
        if (localDriverFile != null) {
            LOGGER.log(Level.INFO, "Driver already in local maven repository ");
        } else {
            Path projectPath = Path.of(projectUri);

            File mvnwFile = projectPath.resolve("mvnw").toFile();

            InvocationRequest request = new DefaultInvocationRequest();
            request.setBatchMode(true);
            request.setOffline(false);
            request.setBaseDirectory(new File(".")); // or use project base directory
            request.setGoals(Collections.singletonList("install:install-file"));

            // Set Maven properties
            Properties props = new Properties();
            props.setProperty("file", filePath);
            props.setProperty("groupId", groupId);
            props.setProperty("artifactId", artifactId);
            props.setProperty("version", version);
            props.setProperty("packaging", "jar");

            request.setProperties(props);

            Invoker invoker = new DefaultInvoker();
            invoker.setMavenHome(projectPath.toFile());
            invoker.setMavenExecutable(mvnwFile);

            InvocationResult result = null;
            try {
                result = invoker.execute(request);
                if (result != null && result.getExitCode() == 0) {
                    isDriverAdded = true;
                    LOGGER.log(Level.INFO, "JAR installed successfully! ");

                } else {
                    isDriverAdded = false;
                    LOGGER.log(Level.INFO,
                            "Failed to install JAR. Exception:  " + result.getExecutionException() + " Exit code:   " +
                                    result.getExitCode());
                }
            } catch (MavenInvocationException e) {
                isDriverAdded = false;
                LOGGER.log(Level.INFO, "Maven Invocation Exception " + e);
            }

        }
        String artifactPath = null;
        if (isDriverAdded) {
            String localMavenRepo = Path.of(System.getProperty(Constant.USER_HOME), Constant.M2, Constant.REPOSITORY)
                    .toString();
            artifactPath = Path.of(localMavenRepo, groupId.replace(".", File.separator), artifactId, version,
                    artifactId + "-" + version + Constant.JAR_EXTENSION).toString();
        }
        return artifactPath;
    }

    /**
     * Get selected driver JAR Maven Coordinates
     */
    public static DriverMavenCoordinatesResponse getDriverMavenCoordinates(String driverPath, String connectorName,
                                                                           String connectionType) {

        DriverMavenCoordinatesResponse response = new DriverMavenCoordinatesResponse();
        response.setFound(false);
        String groupId = null;
        String artifactId = null;
        String version = null;
        if (StringUtils.isBlank(driverPath)) {
            ConnectorHolder connectorHolder;
            connectorHolder = ConnectorHolder.getInstance();
            Connector connector = connectorHolder.getConnector(connectorName);

            String connectorPath = connector.getExtractedConnectorPath();
            File connectorDirectory = Path.of(connectorPath).toFile();
            if (!connectorDirectory.exists() || !connectorDirectory.isDirectory()) {
                LOGGER.log(Level.SEVERE, "Connector directory does not exist: " + connectorDirectory.getAbsolutePath());
                return null;
            }
            // Read descriptor.yml from the connector folder
            File descriptorFile = new File(connectorDirectory, Constant.DESCRIPTOR_FILE);
            if (!descriptorFile.exists()) {
                LOGGER.log(Level.SEVERE, "descriptor.yml not found in connector: " + connectorName);
                return null;
            }

            Map<String, Object> descriptorData;
            try (InputStream inputStream = new FileInputStream(descriptorFile)) {
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                descriptorData = yamlMapper.readValue(inputStream, Map.class);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error reading descriptor.yml: " + e.getMessage());
                return null;
            }

            // Find the driver info that matches the connection type
            Map<String, Object> driverInfo = findDriverForConnectionType(descriptorData, connectionType);
            if (driverInfo == null) {
                LOGGER.log(Level.SEVERE, "No driver found for connection type: " + connectionType);
                return null;
            }

            // Extract driver coordinates
            groupId = (String) driverInfo.get(Constant.GROUP_ID_KEY);
            artifactId = (String) driverInfo.get(Constant.ARTIFACT_ID_KEY);
            version = (String) driverInfo.get(Constant.VERSION_KEY);
            if (StringUtils.isAnyBlank(groupId, artifactId, version)) {
                LOGGER.log(Level.SEVERE, "Invalid driver coordinates in descriptor");
                return null;
            }
        } else {
            LOGGER.log(Level.INFO, "Trying to get the maven coordinates for driver : " + driverPath);
            File driverJar = new File(driverPath);

            // Step 1: Parse artifactId and version from file name
            String fileName = driverJar.getName();
            if (!fileName.endsWith(".jar")) {
                LOGGER.log(Level.INFO, "Invalid file: must be a .jar file");
                return null;
            }
            String baseName = fileName.substring(0, fileName.length() - 4); // remove ".jar"
            int lastDashIndex = baseName.lastIndexOf('-');
            if (lastDashIndex == -1 || lastDashIndex == 0 || lastDashIndex == baseName.length() - 1) {
                LOGGER.log(Level.INFO, "JAR file name does not follow expected format");
                return null;
            }
            artifactId = baseName.substring(0, lastDashIndex);
            version = baseName.substring(lastDashIndex + 1);

            //Step 2: First check local lookup
            groupId = DriverGroupIdLookup.getGroupIdFromArtifactId(artifactId);
            if (groupId.equals("unknown")) {
                LOGGER.log(Level.INFO, "Group ID not found from local lookup for artifactId: " + artifactId);
                // Step 3: Query Maven Central
                String query = "a:" + artifactId + " AND v:" + version;
                String encodedQuery = null;
                try {
                    encodedQuery = URLEncoder.encode(query, "UTF-8");
                    String apiUrl = Constant.MAVEN_CENTRAL_URL + encodedQuery + Constant.MAVEN_SEARCH_PARAM;
                    // Execute HTTP GET request
                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(20_000);
                    conn.setReadTimeout(40_000);
                    conn.setRequestMethod("GET");
                    if (conn.getResponseCode() != 200) {
                        LOGGER.log(Level.INFO, "Failed : HTTP error code : " + conn.getResponseCode());
                    }
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder jsonOutput = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        jsonOutput.append(line);
                    }

                    conn.disconnect();

                    // Parse the result
                    JSONObject json = new JSONObject(jsonOutput.toString());
                    JSONArray docs = json.getJSONObject("response").getJSONArray("docs");

                    if (docs.length() > 0) {
                        JSONObject doc = docs.getJSONObject(0);
                        groupId = doc.getString("g");
                        artifactId = doc.getString("a");
                        version = doc.getString("v");
                    } else {
                        LOGGER.log(Level.INFO, "No match found for artifactId=" + artifactId + ", version=" + version);
                    }

                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error finding driver for connection type: " + e.getMessage());
                    return response;
                }
            }
        }
        if (groupId != null && !groupId.equals("unknown")) {
            response.setFound(true);
            response.setArtifactId(artifactId);
            response.setVersion(version);
            response.setGroupId(groupId);
        }
        return response;
    }
}
