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

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.eclipse.lemminx.customservice.synapse.parser.pom.PomParser.getPomDetails;

/**
 * Manages the downloading of project dependencies defined in a Maven pom.xml file.
 * Handles both connector and integration project dependencies, logging failures if any occur.
 */
public class DependencyDownloadManager {

    private static final Logger LOGGER = Logger.getLogger(ConnectorDownloadManager.class.getName());

    /**
     * Downloads the dependencies specified in the pom.xml file of the given project.
     *
     * @param projectPath The path to the project directory containing the pom.xml file.
     * @return A message indicating the success or failure of the download operation.
     */
    public static String downloadDependencies(String projectPath) {

        StringBuilder errorMessage = new StringBuilder();
        Boolean hasErrors = false;
        OverviewPageDetailsResponse pomDetailsResponse = new OverviewPageDetailsResponse();
        getPomDetails(projectPath, pomDetailsResponse);
        List<DependencyDetails> connectorDependencies =
                pomDetailsResponse.getDependenciesDetails().getConnectorDependencies();
        List<DependencyDetails> integrationProjectDependencies =
                pomDetailsResponse.getDependenciesDetails().getIntegrationProjectDependencies();
        List<String> failedConnectorDependencies =
                ConnectorDownloadManager.downloadDependencies(projectPath, connectorDependencies);
        DependencyDownloadResult failedIntegrationProjectDependencies =
                IntegrationProjectDownloadManager.downloadDependencies(projectPath, integrationProjectDependencies);

        if (!failedConnectorDependencies.isEmpty()) {
            String connectorError = "Some connectors were not downloaded: " + String.join(", ", failedConnectorDependencies);
            LOGGER.log(Level.SEVERE, connectorError);
            errorMessage.append(connectorError);
            hasErrors = true;
        }

        if (!failedIntegrationProjectDependencies.getFailedDependencies().isEmpty()) {
            String projectError = "Following integration project dependencies were unavailable: " +
                    String.join(", ", failedIntegrationProjectDependencies.getFailedDependencies());
            LOGGER.log(Level.SEVERE, projectError);
            if (hasErrors) {
                errorMessage.append(". ");
            }
            errorMessage.append(projectError);
            hasErrors = true;
        }

        if (!failedIntegrationProjectDependencies.getNoDescriptorDependencies().isEmpty()) {
            String descriptorError = "Following dependencies do not contain the descriptor file: " +
                    String.join(", ", failedIntegrationProjectDependencies.getNoDescriptorDependencies());
            LOGGER.log(Level.SEVERE, descriptorError);
            if (hasErrors) {
                errorMessage.append(". ");
            }
            errorMessage.append(descriptorError);
            hasErrors = true;
        }

        if (hasErrors) {
            return errorMessage.toString();
        }
        return "Success";
    }
}
