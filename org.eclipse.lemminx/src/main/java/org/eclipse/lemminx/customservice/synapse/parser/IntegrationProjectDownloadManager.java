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

import org.apache.commons.lang3.StringUtils;
import org.eclipse.lemminx.customservice.synapse.utils.Constant;
import org.eclipse.lemminx.customservice.synapse.utils.Utils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.eclipse.lemminx.customservice.synapse.utils.Constant.DOT;
import static org.eclipse.lemminx.customservice.synapse.utils.Constant.HYPHEN;
import static org.eclipse.lemminx.customservice.synapse.utils.Utils.copyFile;
import static org.eclipse.lemminx.customservice.synapse.utils.Utils.getDependencyFromLocalRepo;

/**
 * Manages the downloading and extraction of integration project dependencies.
 * <p>
 * This class handles the recursive fetching of dependencies for integration projects,
 * including downloading .car files, parsing their descriptor.xml files for additional
 * dependencies, and managing the local storage of these files.
 * </p>
 */
public class IntegrationProjectDownloadManager {

    private static final Logger LOGGER = Logger.getLogger(ConnectorDownloadManager.class.getName());

    /**
     * Handles the downloading and extraction of integration project dependencies.
     * <p>
     * For each provided dependency, this method attempts to fetch the corresponding
     * .car file, recursively resolves and downloads any additional dependencies
     * specified in their descriptor.xml files, and manages local storage of these files.
     * </p>
     *
     * @param projectPath  the file system path of the integration project
     * @param dependencies the list of initial dependencies to process
     * @return a list of dependency identifiers that failed to download or process
     */
    public static List<String> downloadDependencies(String projectPath, List<DependencyDetails> dependencies) {

        String projectId = new File(projectPath).getName() + "_" + Utils.getHash(projectPath);
        File directory = Path.of(System.getProperty(Constant.USER_HOME), Constant.WSO2_MI,
                Constant.INTEGRATION_PROJECT_DEPENDENCIES, projectId).toFile();
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

        List<String> failedDependencies = new ArrayList<>();
        Set<String> fetchedDependencies = new HashSet<>();

        for (DependencyDetails dependency : dependencies) {
            try {
                fetchDependencyRecursively(dependency, downloadDirectory, fetchedDependencies);
            } catch (Exception e) {
                String failedDependency =
                        dependency.getGroupId() + HYPHEN + dependency.getArtifact() + HYPHEN + dependency.getVersion();
                LOGGER.log(Level.WARNING,
                        "Error occurred while downloading dependency " + failedDependency + ": " + e.getMessage());
                failedDependencies.add(failedDependency);
            }
        }
        return failedDependencies;
    }

    /**
     * Recursively fetches the specified dependency and its transitive dependencies.
     * <p>
     * Downloads the .car file for the given dependency, parses its descriptor.xml for additional
     * dependencies, and recursively fetches those as well. Ensures that each dependency is only
     * fetched once per invocation to avoid redundant downloads and infinite loops.
     * </p>
     *
     * @param dependency          the dependency to fetch
     * @param downloadDirectory   the directory to store downloaded .car files
     * @param fetchedDependencies a set of dependency keys already fetched to prevent duplication
     * @throws Exception if fetching or parsing fails
     */
    static void fetchDependencyRecursively(DependencyDetails dependency, File downloadDirectory,
                                           Set<String> fetchedDependencies) throws Exception {

        String dependencyKey = dependency.getGroupId() + ":" + dependency.getArtifact() + ":" + dependency.getVersion();
        if (fetchedDependencies.contains(dependencyKey)) {
            return; // Skip already fetched dependencies
        }

        fetchedDependencies.add(dependencyKey);

        File carFile = fetchDependencyFile(dependency, downloadDirectory);
        if (!carFile.exists()) {
            throw new Exception("Failed to fetch .car file for dependency: " + dependencyKey);
        }

        // Parse the descriptor.xml to find transitive dependencies
        List<DependencyDetails> transitiveDependencies = parseDescriptorFile(carFile);

        // Recursively fetch transitive dependencies
        for (DependencyDetails transitiveDependency : transitiveDependencies) {
            fetchDependencyRecursively(transitiveDependency, downloadDirectory, fetchedDependencies);
        }
    }

    /**
     * Fetches the specified dependency file (.car) from the download directory or local repository.
     * <p>
     * If the dependency file already exists in the download directory, it is returned.
     * Otherwise, attempts to copy it from the local repository. If not found locally,
     * the method is prepared to handle remote downloads
     * </p>
     *
     * @param dependency        the dependency details to fetch
     * @param downloadDirectory the directory to store or locate the downloaded .car file
     * @return the File object representing the dependency .car file
     */
    private static File fetchDependencyFile(DependencyDetails dependency, File downloadDirectory) {

        File dependencyFile = new File(downloadDirectory,
                dependency.getGroupId() + HYPHEN + dependency.getArtifact() + HYPHEN + dependency.getVersion() + DOT +
                        dependency.getType());
        if (dependencyFile.exists() && dependencyFile.isFile()) {
            LOGGER.log(Level.INFO, "Dependency already downloaded: " + dependencyFile.getName());
        } else {
            File existingArtifact = getDependencyFromLocalRepo(dependency.getGroupId(),
                    dependency.getArtifact(), dependency.getVersion(), dependency.getType());
            if (existingArtifact != null) {
                LOGGER.log(Level.INFO, "Copying dependency from local repository: " + dependencyFile.getName());
                try {
                    String newFileName = dependency.getGroupId() + HYPHEN + dependency.getArtifact() + HYPHEN +
                            dependency.getVersion() + DOT + dependency.getType();
                    copyFile(existingArtifact.getPath(), downloadDirectory.getPath(), newFileName);
                } catch (IOException e) {
                    String failedDependency =
                            dependency.getGroupId() + HYPHEN + dependency.getArtifact() + HYPHEN + dependency.getVersion();
                    LOGGER.log(Level.WARNING,
                            "Error occurred while downloading dependency " + failedDependency + ": " + e.getMessage());
                }
            } else {
                // TODO: Download the dependency from the remote repository if not found in the local repository
            }
        }
        return dependencyFile;
    }

    /**
     * Parses the `descriptor.xml` file inside the given .car file to extract dependency information.
     * <p>
     * Opens the .car file as a ZIP archive, locates the `descriptor.xml`, and reads dependency entries,
     * converting them into a list of `DependencyDetails` objects.
     * </p>
     *
     * @param carFile the .car file containing the `descriptor.xml`
     * @return a list of `DependencyDetails` parsed from the descriptor
     * @throws Exception if the file cannot be read or parsed, or if `descriptor.xml` is missing
     */
    private static List<DependencyDetails> parseDescriptorFile(File carFile) throws Exception {

        List<DependencyDetails> dependencies = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(carFile)) {
            ZipEntry descriptorEntry = zipFile.getEntry("descriptor.xml");
            if (descriptorEntry == null) {
                LOGGER.log(Level.INFO, "descriptor.xml not found in .car file: " + carFile.getName());
                return dependencies; // Return empty list if descriptor.xml is missing
            }

            InputStream inputStream = zipFile.getInputStream(descriptorEntry);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            document.getDocumentElement().normalize();

            NodeList dependencyNodes = document.getElementsByTagName("dependency");
            for (int i = 0; i < dependencyNodes.getLength(); i++) {
                Element dependencyElement = (Element) dependencyNodes.item(i);
                String groupId = dependencyElement.getAttribute("groupId");
                String artifactId = dependencyElement.getAttribute("artifactId");
                String version = dependencyElement.getAttribute("version");
                String type = dependencyElement.getAttribute("type");

                if (StringUtils.isNotEmpty(groupId) && StringUtils.isNotEmpty(artifactId)
                        && StringUtils.isNotEmpty(version) && StringUtils.isNotEmpty(type)) {
                    DependencyDetails dependency = new DependencyDetails();
                    dependency.setGroupId(groupId);
                    dependency.setArtifact(artifactId);
                    dependency.setVersion(version);
                    dependency.setType(type);
                    dependencies.add(dependency);
                }
            }
        }
        return dependencies;
    }
}
