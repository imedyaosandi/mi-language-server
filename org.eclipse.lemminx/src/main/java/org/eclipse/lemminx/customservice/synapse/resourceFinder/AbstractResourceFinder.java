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

package org.eclipse.lemminx.customservice.synapse.resourceFinder;

import org.eclipse.lemminx.customservice.synapse.dependency.tree.ArtifactType;
import org.eclipse.lemminx.customservice.synapse.parser.Node;
import org.eclipse.lemminx.customservice.synapse.parser.OverviewPageDetailsResponse;
import org.eclipse.lemminx.customservice.synapse.parser.pom.PomParser;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.ArtifactResource;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.RegistryResource;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.RequestedResource;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.Resource;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.ResourceResponse;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.registryHander.DatamapperHandler;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.registryHander.NonXMLRegistryHandler;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.registryHander.SchemaResourceHandler;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.registryHander.SimpleResourceHandler;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.registryHander.SwaggerResourceHandler;
import org.eclipse.lemminx.customservice.synapse.utils.Constant;
import org.eclipse.lemminx.customservice.synapse.utils.Utils;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.list;

public abstract class AbstractResourceFinder {

    private static final Logger LOGGER = Logger.getLogger(AbstractResourceFinder.class.getName());
    protected static final String ARTIFACTS = "ARTIFACTS";
    protected static final String REGISTRY = "REGISTRY";
    protected static final String LOCAL_ENTRY = "LOCAL_ENTRY";
    protected static final List<String> resourceFromRegistryOnly = List.of("dataMapper", "js", "json", "smooksConfig",
            "wsdl", "ws_policy", "xsd", "xsl", "xslt", "yaml", "registry", "unitTestRegistry", "schema", "swagger");

    // This has the xml tag mapping for each artifact type
    private static final Map<String, String> typeToXmlTagMap = new HashMap<>();
    protected Map<String, ResourceResponse> dependentResourcesMap = new HashMap<>();

    static {

        // Populate the type to xml tag map
        typeToXmlTagMap.put("api", "api");
        typeToXmlTagMap.put("endpoint", "endpoint");
        typeToXmlTagMap.put("sequence", "sequence");
        typeToXmlTagMap.put("messageStore", "messageStore");
        typeToXmlTagMap.put("messageProcessor", "messageProcessor");
        typeToXmlTagMap.put("endpointTemplate", "template");
        typeToXmlTagMap.put("sequenceTemplate", "template");
        typeToXmlTagMap.put("task", "task");
        typeToXmlTagMap.put("localEntry", "localEntry");
        typeToXmlTagMap.put("inbound-endpoint", "inboundEndpoint");
        typeToXmlTagMap.put("dataService", "data");
        typeToXmlTagMap.put("dataSource", "dataSource");
        typeToXmlTagMap.put("ws_policy", "wsp:Policy");
        typeToXmlTagMap.put("smooksConfig", "smooks-resource-list");
        typeToXmlTagMap.put("proxyService", "proxy");
        typeToXmlTagMap.put("xsl", "xsl:stylesheet");
        typeToXmlTagMap.put("xslt", "xsl:stylesheet");
        typeToXmlTagMap.put("xsd", "xs:schema");
        typeToXmlTagMap.put("wsdl", "wsdl:definitions");
    }

    /**
     * Loads dependent resources for the given project path.
     * <p>
     * This method initializes the dependent resources map and attempts to locate
     * the dependencies directory for the specified project. If found, it iterates
     * through each dependent project, finds resources of each type, and merges them
     * into the dependent resources map.
     *
     * @param projectPath the absolute path to the project whose dependencies are to be loaded
     * @throws RuntimeException if an I/O error occurs while accessing the dependencies
     */
    public String loadDependentResources(String projectPath) {

        dependentResourcesMap = new HashMap<>();
        String projectName = new File(projectPath).getName();
        Path projectDependencyDir = findProjectDependencyDir(projectPath);
        if (projectDependencyDir == null) {
            LOGGER.warning("No project dependency directory found for project: " + projectPath);
            return "No dependent integration projects found";
        }

        Path extractedDir = projectDependencyDir.resolve(Constant.EXTRACTED);
        if (!exists(extractedDir) || !isDirectory(extractedDir)) {
            LOGGER.warning("No project dependency extracted directory found for project: " + projectPath);
            return "No dependent integration projects found";
        }

        Map<String, List<String>> duplicates = new HashMap<>();
        // Used to identify any duplicate artifacts across projects
        Map<String, List<String>> artifactNameToProjects = new HashMap<>();

        // Collect main project artifacts
        Map<String, ResourceResponse> allResources = findAllResources(projectPath);
        for (String type : allResources.keySet()) {
            ResourceResponse mainResources = allResources.get(type);
            addArtifactNamesToProjects(mainResources, projectName, artifactNameToProjects);
        }

        try (var dependentProjects = list(extractedDir)) {
            // Iterate over each dependent project directory
            OverviewPageDetailsResponse parentProjectDetails = new OverviewPageDetailsResponse();
            PomParser.getPomDetails(projectPath, parentProjectDetails);
            for (Path dependentProject : dependentProjects.toArray(Path[]::new)) {
                if (isDirectory(dependentProject)) {
                    String projectNameDep = dependentProject.getFileName().toString();
                    OverviewPageDetailsResponse pomDetailsResponse = new OverviewPageDetailsResponse();
                    PomParser.getPomDetails(dependentProject.toString(), pomDetailsResponse);
                    // For each resource type, find resources from the dependent project
                    Map<String, ResourceResponse> dependentProjectAllResources = findAllResources(dependentProject.toString());
                    for (String type : dependentProjectAllResources.keySet()) {
                        ResourceResponse resources = dependentProjectAllResources.get(type);
                        Node isVersionedDeployment = parentProjectDetails.getBuildDetails().getVersionedDeployment();
                        if (isVersionedDeployment != null && Boolean.parseBoolean(isVersionedDeployment.getValue())
                                && resources != null) {
                            // Append project details(group ID and artifact ID) to synapse artifacts
                            if (resources.getResources() != null) {
                                resources.getResources().forEach(resource -> {
                                    resource.setName(getFullyQualifiedName(pomDetailsResponse, resource));
                                });
                            }
                            // Append project details(group ID and artifact ID) to registry artifacts
                            if (resources.getRegistryResources() != null) {
                                resources.getRegistryResources().forEach(resource -> {
                                    ((RegistryResource) resource)
                                            .setRegistryKey(getFullyQualifiedNameForRegistryArtifact(pomDetailsResponse, (RegistryResource) resource));
                                });
                            }
                        }
                        dependentResourcesMap.computeIfAbsent(type, k -> new ResourceResponse());
                        mergeResourceResponses(dependentResourcesMap.get(type), resources);
                        addArtifactNamesToProjects(resources, projectNameDep, artifactNameToProjects);
                    }
                }
            }
        } catch (IOException e) {
            return "Error loading dependent resources: " + e.getMessage();
        }

        // Find duplicated artifacts
        for (Map.Entry<String, List<String>> entry : artifactNameToProjects.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.put(entry.getKey(), entry.getValue());
            }
        }

        if (!duplicates.isEmpty()) {
            String duplicateMsg = generateDuplicateArtifactMessage(duplicates);
            LOGGER.warning(duplicateMsg);
            return duplicateMsg;
        }
        return "Success: Dependent resources loaded successfully for project: " + projectPath;
    }

    public Map<String, ResourceResponse> getDependentResourcesMap() {

        return dependentResourcesMap;
    }

    private String getFullyQualifiedName(OverviewPageDetailsResponse pomDetailsResponse, Resource resource) {

        // For DataServices and proxy services, the name remains unchanged as by default MI server won't expose versioned services
        if (ArtifactType.DATA_SERVICE.name().equals(resource.getType()) || ArtifactType.PROXY_SERVICE.name().equals(resource.getType())) {
            return resource.getName();
        }
        // For other artifact types, the name format will be updated as follows
        // groupID__artifactID__ArtifactName
        return pomDetailsResponse.getBuildDetails().getAdvanceDetails().getProjectGroupId().getValue()
                    + "__" + pomDetailsResponse.getBuildDetails().getAdvanceDetails().getProjectArtifactId().getValue()
                    + "__" + resource.getName();
    }

    private String getFullyQualifiedNameForRegistryArtifact(OverviewPageDetailsResponse pomDetailsResponse, RegistryResource resource) {

        // For registry resource artifact types, the name format will be updated as follows
        // resources:path/groupID__artifactID__ArtifactName
        int lastSlash = resource.getRegistryKey().lastIndexOf('/');
        String dirPath = resource.getRegistryKey().substring(0, lastSlash + 1);
        String resourceName = resource.getRegistryKey().substring(lastSlash + 1);

        String fullyQualifiedName = pomDetailsResponse.getBuildDetails().getAdvanceDetails().getProjectGroupId().getValue()
                + "__" + pomDetailsResponse.getBuildDetails().getAdvanceDetails().getProjectArtifactId().getValue()
                + "__" + resourceName;

        return dirPath + fullyQualifiedName;
    }

    /**
     * Finds the dependency directory for a given project path.
     * <p>
     * This method constructs the expected dependency directory path using the user's home directory,
     * WSO2 MI constants, and a hash of the project path.
     *
     * @param projectPath the absolute path to the project
     * @return the dependency directory as a Path if it exists, or null if not found
     */
    private Path findProjectDependencyDir(String projectPath) {

        Path dependenciesDir = Path.of(
                System.getProperty(Constant.USER_HOME),
                Constant.WSO2_MI,
                Constant.INTEGRATION_PROJECT_DEPENDENCIES
        );
        String projectName = new File(projectPath).getName();
        String hashedPath = Utils.getHash(projectPath);
        Path expectedDir = dependenciesDir.resolve(projectName + Constant.UNDERSCORE + hashedPath);
        if (exists(expectedDir) && isDirectory(expectedDir)) {
            return expectedDir;
        }
        return null;
    }

    /**
     * Adds artifact names from the given ResourceResponse to the artifactNameToProjects map,
     * associating each artifact name with the dependent project name.
     *
     * @param resources              the ResourceResponse containing resources to process
     * @param projectNameDep         the name of the dependent project
     * @param artifactNameToProjects the map to update with artifact names and their associated projects
     */
    private void addArtifactNamesToProjects(ResourceResponse resources, String projectNameDep,
                                            Map<String, List<String>> artifactNameToProjects) {

        if (resources != null && resources.getResources() != null) {
            for (Resource resource : resources.getResources()) {
                String name = resource.getName();
                if (name != null) {
                    artifactNameToProjects.computeIfAbsent(name, k -> new ArrayList<>()).add(projectNameDep);
                }
            }
        }
    }

    /**
     * Generates a detailed message describing artifacts that were found among multiple dependent projects.
     *
     * @param duplicates a map where the key is the artifact name and the value is a list of project names containing the artifact
     * @return a formatted string message listing duplicate artifacts and their locations
     */
    private String generateDuplicateArtifactMessage(Map<String, List<String>> duplicates) {

        StringBuilder duplicateMsg = new StringBuilder();
        duplicateMsg.append("DUPLICATE ARTIFACTS\n\n");
        for (Map.Entry<String, List<String>> entry : duplicates.entrySet()) {
            duplicateMsg.append("Artifact: '").append(entry.getKey())
                    .append("' found in: ").append(entry.getValue()).append("\n");
        }
        duplicateMsg.append("\n");
        duplicateMsg.append("Please avoid having artifacts with the same name and continue.\n");
        return duplicateMsg.toString();
    }

    /**
     * Merges the resources and registry resources from two {@link ResourceResponse} objects.
     * <p>
     * If both responses are non-null, their resource lists and registry resource lists are combined to {@code response1}.
     * If {@code response1} is null, a new {@link ResourceResponse} is created and populated with the resources from {@code response2}.
     *
     * @param response1 the target {@link ResourceResponse} to merge into (may be null)
     * @param response2 the source {@link ResourceResponse} to merge from (may be null)
     */
    protected void mergeResourceResponses(ResourceResponse response1, ResourceResponse response2) {

        if (response1 != null && response2 != null) {
            List<Resource> resources = response1.getResources();
            if (resources == null) {
                resources = new ArrayList<>();
            }
            if (response2.getResources() != null) {
                resources.addAll(response2.getResources());
            }
            response1.setResources(resources);
            List<Resource> registryResources = response1.getRegistryResources();
            if (registryResources == null) {
                registryResources = new ArrayList<>();
            }
            if (response2.getRegistryResources() != null) {
                registryResources.addAll(response2.getRegistryResources());
            }
            response1.setRegistryResources(registryResources);
        } else if (response1 == null) {
            response1 = new ResourceResponse();
            response1.setResources(response2.getResources());
            response1.setRegistryResources(response2.getRegistryResources());
        }
    }

    public ResourceResponse getAvailableResources(String uri, Either<String, List<RequestedResource>> resourceTypes) {

        ResourceResponse response = null;
        if (uri != null) {
            if (resourceTypes.isLeft()) {
                response = findResources(uri, resourceTypes.getLeft());
            } else {
                response = findResources(uri, resourceTypes.getRight());
            }
        }
        return response;
    }

    private ResourceResponse findResources(String projectPath, String type) {

        RequestedResource requestedResource = new RequestedResource();
        requestedResource.type = type;
        requestedResource.needRegistry = true;
        return findResources(projectPath, List.of(requestedResource));
    }

    /**
     * Scans the given registry directory and populates the provided map with all registry resources found.
     *
     * @param registryPath the path to the registry directory to scan
     * @param allResources the map to populate with found resources, keyed by resource type
     */
    protected void findAllRegistryResources(Path registryPath, Map<String, ResourceResponse> allResources) {

        File folder = registryPath.toFile();
        if (!folder.exists()) {
            return;
        }
        traverseRegistryFolder(folder, allResources);
    }

    /**
     * Recursively traverses the given registry folder and populates the provided map with all registry resources found.
     *
     * @param folder       the root folder to traverse for registry resources
     * @param allResources the map to populate with found resources, keyed by resource type
     */
    private void traverseRegistryFolder(File folder, Map<String, ResourceResponse> allResources) {

        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                traverseRegistryFolder(file, allResources); // recursive
            } else {
                Resource resource = createRegistryResourceFromFile(file);
                if (resource != null) {
                    String type = resource.getType();
                    ResourceResponse response = allResources.computeIfAbsent(type, k -> new ResourceResponse());
                    List<Resource> resources = response.getRegistryResources();
                    if (resources == null) {
                        resources = new ArrayList<>();
                        response.setRegistryResources(resources);
                    }
                    resources.add(resource);
                }
            }
        }
    }

    protected abstract ResourceResponse findResources(String projectPath, List<RequestedResource> type);

    /**
     * Finds and returns all resources for the given project path, grouped by resource type.
     *
     * @param projectPath the absolute path to the project
     * @return a map where the key is the resource type and the value is the corresponding ResourceResponse
     */
    public abstract Map<String, ResourceResponse> findAllResources(String projectPath);

    protected List<Resource> findResourceInArtifacts(Path artifactsPath, List<RequestedResource> types) {

        List<Resource> resources = new ArrayList<>();
        for (RequestedResource requestedResource : types) {
            if (!resourceFromRegistryOnly.contains(requestedResource.type)) {
                String type = requestedResource.type;
                String resourceTypeFolder = getArtifactFolder(type);
                if (resourceTypeFolder != null) {
                    Path resourceFolderPath = Path.of(artifactsPath.toString(), resourceTypeFolder);
                    File folder = new File(resourceFolderPath.toString());
                    File[] listOfFiles = folder.listFiles();
                    if (listOfFiles != null) {
                        List<Resource> resources1 = createResources(List.of(listOfFiles), type, ARTIFACTS);
                        resources.addAll(resources1);
                    }
                }
            }
        }
        return resources;
    }

    protected List<Resource> findResourceInLocalEntry(Path localEntryPath, List<RequestedResource> types) {

        List<Resource> resources = new ArrayList<>();
        File folder = localEntryPath.toFile();

        if (folder.exists()) {
            for (RequestedResource requestedResource : types) {
                File[] listOfFiles = folder.listFiles();
                if (listOfFiles != null) {
                    List<Resource> resources1 = createResources(List.of(listOfFiles), requestedResource.type,
                            LOCAL_ENTRY);
                    resources.addAll(resources1);
                }

            }
        }
        return resources;
    }

    /**
     * Scans the specified local entry directory and adds all valid local entry resources
     * to the provided map of all resources, grouped by their type.
     *
     * @param localEntryPath the path to the local entry directory
     * @param allResources   the map to populate with found resources, keyed by resource type
     */
    protected void findAllLocalEntryResources(Path localEntryPath, Map<String, ResourceResponse> allResources) {
        File folder = localEntryPath.toFile();
        if (!folder.exists()) {
            return;
        }

        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            Resource resource = createLocalEntryResource(file);
            if (resource != null) {
                String type = resource.getType();
                ResourceResponse response = allResources.computeIfAbsent(type, k -> new ResourceResponse());
                List<Resource> resources = response.getResources();
                if (resources == null) {
                    resources = new ArrayList<>();
                    response.setResources(resources);
                }
                resources.add(resource);
            }
        }
    }

    /**
     * Creates a Resource object representing a local entry from the given file.
     *
     * This method parses the provided file as an XML document, retrieves the root element,
     * and attempts to identify the artifact type from the first child element. If successful,
     * it creates and returns a Resource for the local entry; otherwise, it returns null.
     *
     * @param file the file representing the local entry
     * @return a Resource object for the local entry, or null if the file is invalid or cannot be parsed
     */
    private Resource createLocalEntryResource(File file) {

        try {
            DOMDocument document = Utils.getDOMDocument(file);
            DOMElement rootElement = document.getDocumentElement();
            if (rootElement == null) {
                return null;
            }

            // Get first artifact element and identify type
            DOMElement artifactElt = Utils.getFirstElement(rootElement);
            if (artifactElt == null) {
                return null;
            }

            String artifactType = artifactElt.getNodeName();
            return createResource(file, artifactType, Constant.LOCAL_ENTRY);
        } catch (IOException e) {
            LOGGER.warning("Error reading local entry file: " + file.getName());
        }
        return null;
    }

    protected abstract String getArtifactFolder(String type);

    protected List<Resource> findResourceInRegistry(Path registryPath, List<RequestedResource> requestedResources) {

        List<Resource> resources = new ArrayList<>();
        File folder = registryPath.toFile();
        boolean isRegistryTypeRequested =
                requestedResources.stream().anyMatch(requestedResource -> "registry".equals(requestedResource.type) ||
                        "unitTestRegistry".equals(requestedResource.type));
        if (isRegistryTypeRequested) {
            traverseFolder(folder, null, null, resources);
        } else {
            HashMap<String, String> requestedTypeToXmlTagMap = getRequestedTypeToXmlTagMap(requestedResources);
            NonXMLRegistryHandler nonXMLRegistryHandler = getNonXMLRegistryHandler(requestedResources, resources);
            traverseFolder(folder, requestedTypeToXmlTagMap, nonXMLRegistryHandler, resources);
        }
        return resources;
    }

    private NonXMLRegistryHandler getNonXMLRegistryHandler(List<RequestedResource> requestedResources,
                                                           List<Resource> resources) {

        NonXMLRegistryHandler handler = null;
        if (hasRequestedResourceOfType(requestedResources, "swagger")) {
            handler = new SwaggerResourceHandler(resources);
        }
        if (hasRequestedResourceOfType(requestedResources, "schema")) {
            if (handler == null) {
                handler = new SchemaResourceHandler(resources);
            } else {
                handler.setNextHandler(new SchemaResourceHandler(resources));
            }
        }
        if (hasRequestedResourceOfType(requestedResources, "dataMapper")) {
            if (handler == null) {
                handler = new DatamapperHandler(resources);
            } else {
                handler.setNextHandler(new DatamapperHandler(resources));
            }
        }
        for (RequestedResource requestedResource : requestedResources) {
            if (requestedResource.type.equals("schema") || requestedResource.type.equals("swagger") || requestedResource.type.equals("dataMapper")) {
                continue;
            }
            if (handler == null) {
                handler = new SimpleResourceHandler(requestedResources, resources);
            } else {
                handler.setNextHandler(new SimpleResourceHandler(requestedResources, resources));
            }
            break;
        }
        return handler;
    }

    private boolean hasRequestedResourceOfType(List<RequestedResource> requestedResources, String type) {

        return requestedResources.stream()
                .anyMatch(requestedResource -> type.equals(requestedResource.type) && requestedResource.needRegistry);
    }

    private HashMap<String, String> getRequestedTypeToXmlTagMap(List<RequestedResource> requestedResources) {

        HashMap<String, String> requestedTypeToXmlTagMap = new HashMap<>();
        requestedResources.forEach(requestedResource -> {
            String type = requestedResource.type;
            String xmlTag = typeToXmlTagMap.get(type);
            if (xmlTag != null && requestedResource.needRegistry) {
                requestedTypeToXmlTagMap.put(type, xmlTag);
            }
        });
        return requestedTypeToXmlTagMap;
    }

    private void traverseFolder(File folder, HashMap<String, String> requestedTypeToXmlTagMap,
                                NonXMLRegistryHandler handler, List<Resource> resources) {

        File[] listOfFiles = folder.listFiles();
        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                if (file.isDirectory()) {
                    if (!".meta".equals(file.getName())) {
                        traverseFolder(file, requestedTypeToXmlTagMap, handler, resources);
                    }
                } else if (file.isFile()) {
                    if (Utils.isRegistryPropertiesFile(file)) {
                        continue;
                    }
                    if (file.getAbsolutePath().endsWith(Path.of(Constant.RESOURCES, Constant.ARTIFACT_XML).toString()) ||
                            file.getAbsolutePath().endsWith(Path.of(Constant.RESOURCES, Constant.REGISTRY, Constant.ARTIFACT_XML).toString())) {
                        continue;
                    }
                    if (handler == null && requestedTypeToXmlTagMap == null) {
                        Resource resource = createNonXmlResource(file, Constant.REGISTRY, REGISTRY);
                        if (resource != null) {
                            resources.add(resource);
                        }
                        continue;
                    }
                    Pattern pattern = Pattern.compile(".*\\.(.*)$");
                    Matcher matcher = pattern.matcher(file.getName());
                    if (matcher.find()) {
                        String fileExtension = matcher.group(1);
                        if (Constant.XML.equals(fileExtension)) {
                            Resource resource = createResource(file, requestedTypeToXmlTagMap, REGISTRY);
                            if (resource != null) {
                                resources.add(resource);
                            } else {
                                handler.handleFile(file);
                            }
                        } else {
                            handler.handleFile(file);
                        }
                    }
                }
            }
        }
    }

    private boolean isFileInRegistry(File file) {

        return file.getAbsolutePath().contains(Constant.GOV) || file.getAbsolutePath().contains(Constant.CONF);
    }

    private Resource createResource(File file, HashMap<String, String> requestedTypeToXmlTagMap, String from) {

        try {
            DOMDocument document = Utils.getDOMDocument(file);
            if (document != null && document.getDocumentElement() != null) {
                DOMElement rootElement = Utils.getRootElement(document);
                if (rootElement != null) {
                    String type = rootElement.getNodeName();
                    if (type != null && requestedTypeToXmlTagMap.containsValue(type)) {
                        Resource resource = null;
                        if (ARTIFACTS.equals(from)) {
                            resource = createArtifactResource(file, rootElement, type, Boolean.FALSE);
                        } else if (REGISTRY.equals(from)) {
                            resource = createRegistryResource(file, rootElement, type);
                        }
                        return resource;
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Error while reading file: " + file.getName() + " to create resource object");
        }
        return null;
    }

    protected List<Resource> createResources(List<File> files, String type, String from) {

        List<Resource> resources = new ArrayList<>();
        for (File file : files) {
            Resource resource = createResource(file, type, from);
            if (resource != null) {
                resources.add(resource);
            }
        }
        return resources;
    }

    private Resource createResource(File file, String type, String from) {

        try {
            DOMDocument document = Utils.getDOMDocument(file);
            DOMElement rootElement;
            String nodeName;
            if (LOCAL_ENTRY.equals(from)) {
                nodeName = Constant.LOCAL_ENTRY;
            } else {
                nodeName = typeToXmlTagMap.get(type);
            }
            rootElement = (DOMElement) Utils.getChildNodeByName(document, nodeName);
            if (rootElement != null && checkValid(rootElement, type, from)) {
                Resource resource = null;
                if (ARTIFACTS.equals(from)) {
                    resource = createArtifactResource(file, rootElement, type, Boolean.FALSE);
                } else if (REGISTRY.equals(from)) {
                    resource = createRegistryResource(file, rootElement, type);
                } else if (LOCAL_ENTRY.equals(from)) {
                    resource = createArtifactResource(file, rootElement, type, Boolean.TRUE);
                }
                return resource;
            }
        } catch (IOException e) {
            LOGGER.warning("Error while reading file: " + file.getName() + " to create resource object");
        }
        return null;
    }

    private Resource createNonXmlResource(File file, String type, String registry) {

        Resource resource = new RegistryResource();
        resource.setName(file.getName());
        resource.setType(type);
        resource.setFrom(registry);
        ((RegistryResource) resource).setRegistryPath(file.getAbsolutePath());
        if (Utils.isFileInRegistry(file)) {
            resource.setFrom(Constant.REGISTRY);
            ((RegistryResource) resource).setRegistryKey(Utils.getRegistryKey(file));
        } else {
            resource.setFrom(Constant.RESOURCES);
            ((RegistryResource) resource).setRegistryKey(Utils.getResourceKey(file));
        }
        return resource;
    }

    private boolean checkValid(DOMElement rootElement, String type, String from) {

        String nodeName = rootElement.getNodeName();
        if (LOCAL_ENTRY.equals(from)) {
            String xmlTag = typeToXmlTagMap.containsKey(type) ? typeToXmlTagMap.get(type) : type;
            DOMElement artifactElt = Utils.getFirstElement(rootElement);
            if (artifactElt != null) {
                String artifactType = artifactElt.getNodeName();
                return xmlTag.equals(artifactType);
            }
            return false;
        } else if (Constant.TEMPLATE.equals(nodeName)) {
            if ("sequenceTemplate".equals(type)) {
                DOMElement sequenceElement = (DOMElement) Utils.getChildNodeByName(rootElement, Constant.SEQUENCE);
                if (sequenceElement != null) {
                    return true;
                }
            } else if ("endpointTemplate".equals(type)) {
                DOMElement endpointElement = (DOMElement) Utils.getChildNodeByName(rootElement, Constant.ENDPOINT);
                if (endpointElement != null) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private Resource createArtifactResource(File file, DOMElement rootElement, String type, boolean isLocalEntry) {

        Resource artifact = new ArtifactResource();
        String name = getArtifactName(rootElement);
        if (name != null) {
            artifact.setName(name);
            artifact.setType(type);
            artifact.setFrom(ARTIFACTS);
            ((ArtifactResource) artifact).setLocalEntry(isLocalEntry);
            ((ArtifactResource) artifact).setArtifactPath(file.getName());
            ((ArtifactResource) artifact).setAbsolutePath(file.getAbsolutePath());
            return artifact;
        }
        return null;
    }

    private Resource createRegistryResource(File file, DOMElement rootElement, String type) {

        Resource registry = new RegistryResource();
        String name = getArtifactName(rootElement);
        if (name == null) {
            name = file.getName();
        }
        registry.setName(name);
        type = type.replace(":", "");
        registry.setType(type);
        registry.setFrom(REGISTRY);
        ((RegistryResource) registry).setRegistryPath(file.getAbsolutePath());
        if (Utils.isFileInRegistry(file)) {
            registry.setFrom(Constant.REGISTRY);
            ((RegistryResource) registry).setRegistryKey(Utils.getRegistryKey(file));
        } else {
            registry.setFrom(Constant.RESOURCES);
            ((RegistryResource) registry).setRegistryKey(Utils.getResourceKey(file));
        }
        return registry;
    }

    private String getArtifactName(DOMElement rootElement) {

        if (isApiArtifact(rootElement)) {
            return getApiArtifactName(rootElement);
        } else {
            return getNonApiArtifactName(rootElement);
        }
    }

    private boolean isApiArtifact(DOMElement rootElement) {

        return Constant.API.equalsIgnoreCase(rootElement.getNodeName());
    }

    private String getApiArtifactName(DOMElement rootElement) {

        StringBuilder name = new StringBuilder();
        name.append(rootElement.getAttribute(Constant.NAME));
        if (rootElement.hasAttribute(Constant.VERSION)) {
            name.append(":v").append(rootElement.getAttribute(Constant.VERSION));
        }
        return name.toString();
    }

    private String getNonApiArtifactName(DOMElement rootElement) {

        if (rootElement.hasAttribute(Constant.NAME)) {
            return rootElement.getAttribute(Constant.NAME);
        } else if (rootElement.hasAttribute(Constant.KEY)) {
            return rootElement.getAttribute(Constant.KEY);
        } else {
            DOMNode nameNode = Utils.getChildNodeByName(rootElement, Constant.NAME);
            if (nameNode != null) {
                return Utils.getInlineString(nameNode.getFirstChild());
            }
            return null;
        }
    }

    /**
     * Creates a Resource object from the given registry file.
     * <p>
     * If the file is an XML file, it parses the file, detects the resource type from the root element,
     * and creates a registry resource. If the file is not XML, it uses the file extension as the type
     * and creates a non-XML registry resource.
     *
     * @param file the registry file to process
     * @return a Resource representing the registry file, or null if the file is invalid or cannot be parsed
     */
    protected Resource createRegistryResourceFromFile(File file) {
        try {
            String fileName = file.getName();
            String detectedType = null;

            if (fileName.endsWith(Constant.XML_EXTENSION)) {
                // Handle XML files: parse and detect type
                DOMDocument document = Utils.getDOMDocument(file);
                DOMElement rootElement = document.getDocumentElement();
                if (rootElement == null) {
                    return null;
                }

                // Look for a matching type in typeToXmlTagMap
                for (Map.Entry<String, String> entry : typeToXmlTagMap.entrySet()) {
                    if (entry.getValue().equals(rootElement.getNodeName())) {
                        if (entry.getValue().equals(Constant.TEMPLATE)) {
                            detectedType = getTemplateType(rootElement);
                        } else {
                            detectedType = entry.getKey();
                        }
                    }
                }

                if (detectedType == null) {
                    // fallback: use root element name as type
                    detectedType = rootElement.getNodeName();
                }

                return createRegistryResource(file, rootElement, detectedType);

            } else {
                // Handle non-XML files: use file extension as type
                int dotIndex = fileName.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
                    detectedType = fileName.substring(dotIndex + 1); // extension only
                } else {
                    detectedType = "unknown"; // fallback if no extension
                }
                return createNonXmlResource(file, detectedType, REGISTRY);
            }

        } catch (IOException e) {
            LOGGER.warning("Error reading registry file: " + file.getName());
        }
        return null;
    }


    /**
     * Identifies the type of template defined by the given root XML element.
     * <p>
     * Determines the template type based on the first child element:
     * <ul>
     *   <li>Returns "sequenceTemplate" if the first child is a sequence</li>
     *   <li>Returns "endpointTemplate" if the first child is an endpoint</li>
     *   <li>Returns "template" in all other cases</li>
     * </ul>
     *
     * @param rootElement the root DOM element representing the template
     * @return a string indicating the template type ("sequenceTemplate", "endpointTemplate", or "template")
     */
    private String getTemplateType(DOMElement rootElement) {
        if (rootElement != null && Constant.TEMPLATE.equals(rootElement.getNodeName())) {
            if (rootElement.getChildren() != null && !rootElement.getChildren().isEmpty()) {
                String firstChildNodeName = rootElement.getChildren().get(0).getNodeName();
                if (Constant.SEQUENCE.equals(firstChildNodeName)) {
                    return Constant.SEQUENCE_TEMPLATE;
                } else if (Constant.ENDPOINT.equals(firstChildNodeName)) {
                    return Constant.ENDPOINT_TEMPLATE;
                }
            }
        }
        return Constant.TEMPLATE;
    }
}
