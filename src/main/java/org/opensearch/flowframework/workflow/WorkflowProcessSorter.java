/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.flowframework.workflow;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.flowframework.common.FlowFrameworkSettings;
import org.opensearch.flowframework.common.WorkflowResources;
import org.opensearch.flowframework.exception.FlowFrameworkException;
import org.opensearch.flowframework.model.ResourceCreated;
import org.opensearch.flowframework.model.Template;
import org.opensearch.flowframework.model.Workflow;
import org.opensearch.flowframework.model.WorkflowEdge;
import org.opensearch.flowframework.model.WorkflowNode;
import org.opensearch.flowframework.util.ParseUtils;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.plugins.PluginsService;
import org.opensearch.threadpool.ThreadPool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.opensearch.flowframework.common.CommonValue.PROVISION_WORKFLOW;
import static org.opensearch.flowframework.common.CommonValue.PROVISION_WORKFLOW_THREAD_POOL;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.MAX_WORKFLOW_STEPS;
import static org.opensearch.flowframework.model.WorkflowNode.NODE_TIMEOUT_DEFAULT_VALUE;
import static org.opensearch.flowframework.model.WorkflowNode.NODE_TIMEOUT_FIELD;
import static org.opensearch.flowframework.model.WorkflowNode.USER_INPUTS_FIELD;
import static org.opensearch.flowframework.workflow.WorkflowStepFactory.WorkflowSteps.getInputByWorkflowType;
import static org.opensearch.flowframework.workflow.WorkflowStepFactory.WorkflowSteps.getOutputByWorkflowType;
import static org.opensearch.flowframework.workflow.WorkflowStepFactory.WorkflowSteps.getRequiredPluginsByWorkflowType;
import static org.opensearch.flowframework.workflow.WorkflowStepFactory.WorkflowSteps.getTimeoutByWorkflowType;

/**
 * Converts a workflow of nodes and edges into a topologically sorted list of Process Nodes.
 */
public class WorkflowProcessSorter {

    private static final Logger logger = LogManager.getLogger(WorkflowProcessSorter.class);

    /** Workflow step types which may not be used in a template */
    public static final Set<String> WORKFLOW_STEP_DENYLIST = Set.of(
        DeleteIndexStep.NAME,
        DeleteIngestPipelineStep.NAME,
        DeleteSearchPipelineStep.NAME
    );

    private WorkflowStepFactory workflowStepFactory;
    private ThreadPool threadPool;
    private Integer maxWorkflowSteps;

    /**
     * Instantiate this class.
     *
     * @param workflowStepFactory The factory which matches template step types to instances.
     * @param threadPool The OpenSearch Thread pool to pass to process nodes.
     * @param flowFrameworkSettings settings of the plugin
     */
    public WorkflowProcessSorter(
        WorkflowStepFactory workflowStepFactory,
        ThreadPool threadPool,
        FlowFrameworkSettings flowFrameworkSettings
    ) {
        this.workflowStepFactory = workflowStepFactory;
        this.threadPool = threadPool;
        this.maxWorkflowSteps = flowFrameworkSettings.getMaxWorkflowSteps();
    }

    /**
     * Sort a workflow into a topologically sorted list of process nodes.
     * @param workflow A workflow with (unsorted) nodes and edges which define predecessors and successors
     * @param workflowId The workflowId associated with the step
     * @param params Parameters passed on the REST path
     * @param tenantId The tenantId associated with the step
     * @return A list of Process Nodes sorted topologically.  All predecessors of any node will occur prior to it in the list.
     */
    public List<ProcessNode> sortProcessNodes(Workflow workflow, String workflowId, Map<String, String> params, String tenantId) {
        if (workflow.nodes().size() > this.maxWorkflowSteps) {
            throw new FlowFrameworkException(
                "Workflow "
                    + workflowId
                    + " has "
                    + workflow.nodes().size()
                    + " nodes, which exceeds the maximum of "
                    + this.maxWorkflowSteps
                    + ". Change the setting ["
                    + MAX_WORKFLOW_STEPS.getKey()
                    + "] to increase this.",
                RestStatus.BAD_REQUEST
            );
        }
        // Disallow some steps
        for (WorkflowNode node : workflow.nodes()) {
            if (WORKFLOW_STEP_DENYLIST.contains(node.type())) {
                throw new FlowFrameworkException(
                    "The step type [" + node.type() + "] for node [" + node.id() + "] can not be used in a workflow.",
                    RestStatus.FORBIDDEN
                );
            }
        }
        List<WorkflowNode> sortedNodes = topologicalSort(workflow.nodes(), workflow.edges());

        List<ProcessNode> nodes = new ArrayList<>();
        Map<String, ProcessNode> idToNodeMap = new HashMap<>();
        for (WorkflowNode node : sortedNodes) {
            WorkflowStep step = workflowStepFactory.createStep(node.type());
            WorkflowData data = new WorkflowData(node.userInputs(), workflow.userParams(), workflowId, node.id());
            List<ProcessNode> predecessorNodes = workflow.edges()
                .stream()
                .filter(e -> e.destination().equals(node.id()))
                // since we are iterating in topological order we know all predecessors will be in the map
                .map(e -> idToNodeMap.get(e.source()))
                .collect(Collectors.toList());

            TimeValue nodeTimeout = parseTimeout(node);
            ProcessNode processNode = new ProcessNode(
                node.id(),
                step,
                node.previousNodeInputs(),
                params,
                data,
                predecessorNodes,
                threadPool,
                PROVISION_WORKFLOW_THREAD_POOL,
                nodeTimeout,
                tenantId
            );
            idToNodeMap.put(processNode.id(), processNode);
            nodes.add(processNode);
        }
        return nodes;
    }

    /**
     * Sort an updated workflow into a topologically sorted list of create/update process nodes
     * @param workflowId the workflow ID associated with the template
     * @param originalTemplate the original template currently indexed
     * @param updatedTemplate the updated template to be executed
     * @param resourcesCreated the resources previously created for the workflow
     * @param tenantId the tenant id
     * @throws Exception for issues creating the reprovision sequence
     * @return A list of ProcessNode
     */
    public List<ProcessNode> createReprovisionSequence(
        String workflowId,
        Template originalTemplate,
        Template updatedTemplate,
        List<ResourceCreated> resourcesCreated,
        String tenantId
    ) throws Exception {

        Workflow updatedWorkflow = updatedTemplate.workflows().get(PROVISION_WORKFLOW);
        if (updatedWorkflow.nodes().size() > this.maxWorkflowSteps) {
            throw new FlowFrameworkException(
                "Workflow "
                    + workflowId
                    + " has "
                    + updatedWorkflow.nodes().size()
                    + " nodes, which exceeds the maximum of "
                    + this.maxWorkflowSteps
                    + ". Change the setting ["
                    + MAX_WORKFLOW_STEPS.getKey()
                    + "] to increase this.",
                RestStatus.BAD_REQUEST
            );
        }

        // Topologically sort the updated workflow
        List<WorkflowNode> sortedUpdatedNodes = topologicalSort(updatedWorkflow.nodes(), updatedWorkflow.edges());

        // Convert original template into node id map
        Map<String, WorkflowNode> originalTemplateMap = originalTemplate.workflows()
            .get(PROVISION_WORKFLOW)
            .nodes()
            .stream()
            .collect(Collectors.toMap(WorkflowNode::id, node -> node));

        // Temporarily block node deletions until fine-grained deprovisioning is implemented
        if (!originalTemplateMap.values().stream().allMatch(sortedUpdatedNodes::contains)) {
            throw new FlowFrameworkException(
                "Workflow Step deletion is not supported when reprovisioning a template.",
                RestStatus.BAD_REQUEST
            );
        }

        List<ProcessNode> reprovisionSequence = createReprovisionSequence(
            workflowId,
            updatedWorkflow,
            sortedUpdatedNodes,
            originalTemplateMap,
            resourcesCreated,
            tenantId
        );

        // If the reprovision sequence consists entirely of WorkflowDataSteps, then no modifications were made to the exisiting template.
        if (reprovisionSequence.stream().allMatch(n -> n.workflowStep().getName().equals(WorkflowDataStep.NAME))) {
            throw new FlowFrameworkException("Template does not contain any modifications", RestStatus.BAD_REQUEST);
        }

        return reprovisionSequence;
    }

    /**
     * Compares an original and upated template and creates a list of update, create or workflowdatastep nodes
     * @param workflowId the workflow ID associated with the template
     * @param updatedWorkflow the updated workflow to be processed
     * @param sortedUpdatedNodes the topologically sorted updated template nodes
     * @param originalTemplateMap a map of node Id to workflow node of the original template
     * @param resourcesCreated a list of resources created for this template
     * @param tenantId the tenant id
     * @return a list of process node representing the reprovision sequence
     * @throws Exception for issues creating the reprovision sequence
     */
    private List<ProcessNode> createReprovisionSequence(
        String workflowId,
        Workflow updatedWorkflow,
        List<WorkflowNode> sortedUpdatedNodes,
        Map<String, WorkflowNode> originalTemplateMap,
        List<ResourceCreated> resourcesCreated,
        String tenantId
    ) throws Exception {
        Map<String, ProcessNode> idToNodeMap = new HashMap<>();
        List<ProcessNode> reprovisionSequence = new ArrayList<>();

        for (WorkflowNode node : sortedUpdatedNodes) {
            ProcessNode processNode = createProcessNode(
                updatedWorkflow,
                node,
                originalTemplateMap,
                resourcesCreated,
                workflowId,
                idToNodeMap,
                tenantId
            );
            if (processNode != null) {
                idToNodeMap.put(processNode.id(), processNode);
                reprovisionSequence.add(processNode);
            }
        }

        return reprovisionSequence;
    }

    /**
     * Determines which type of process node to create for a reprovision sequence
     * @param updatedWorkflow the updated workflow to be processed
     * @param node the current workflow node
     * @param originalTemplateMap a map of node Id to workflow node of the original template
     * @param resourcesCreated a list of resources created for this template
     * @param workflowId the workflow ID associated with the template
     * @param idToNodeMap a map of the current reprovision sequence
     * @param tenantId the tenant id
     * @return a ProcessNode
     * @throws Exception for issues creating the process node
     */
    private ProcessNode createProcessNode(
        Workflow updatedWorkflow,
        WorkflowNode node,
        Map<String, WorkflowNode> originalTemplateMap,
        List<ResourceCreated> resourcesCreated,
        String workflowId,
        Map<String, ProcessNode> idToNodeMap,
        String tenantId
    ) throws Exception {
        WorkflowData data = new WorkflowData(node.userInputs(), updatedWorkflow.userParams(), workflowId, node.id());
        List<ProcessNode> predecessorNodes = updatedWorkflow.edges()
            .stream()
            .filter(e -> e.destination().equals(node.id()))
            // since we are iterating in topological order we know all predecessors will be in the map
            .map(e -> idToNodeMap.get(e.source()))
            .collect(Collectors.toList());
        TimeValue nodeTimeout = parseTimeout(node);

        if (!originalTemplateMap.containsKey(node.id())) {
            // Case 1: Additive modification, create new node
            return createNewProcessNode(node, data, predecessorNodes, nodeTimeout, tenantId);
        } else {
            WorkflowNode originalNode = originalTemplateMap.get(node.id());
            if (shouldUpdateNode(node, originalNode)) {
                // Case 2: Existing modification, create update step
                return createUpdateProcessNode(node, data, predecessorNodes, nodeTimeout, tenantId);
            } else {
                // Case 4: No modification to existing node, create proxy step
                return createWorkflowDataStepNode(node, data, predecessorNodes, nodeTimeout, resourcesCreated, tenantId);
            }
        }
    }

    /**
     * Creates a process node to create a new resource
     * @param node the current node
     * @param data the current node data
     * @param predecessorNodes the current node predecessors
     * @param nodeTimeout the current node timeout
     * @param tenantId the tenant id
     * @return a Process Node
     */
    private ProcessNode createNewProcessNode(
        WorkflowNode node,
        WorkflowData data,
        List<ProcessNode> predecessorNodes,
        TimeValue nodeTimeout,
        String tenantId
    ) {
        WorkflowStep step = workflowStepFactory.createStep(node.type());
        return new ProcessNode(
            node.id(),
            step,
            node.previousNodeInputs(),
            Collections.emptyMap(), // TODO Add support to reprovision substitution templates
            data,
            predecessorNodes,
            threadPool,
            PROVISION_WORKFLOW_THREAD_POOL,
            nodeTimeout,
            tenantId
        );
    }

    /**
     * Creates a process node to update an existing resource
     * @param node the current node
     * @param data the current node data
     * @param predecessorNodes the current node predecessors
     * @param nodeTimeout the current node timeout
     * @param tenantId the tenant id
     * @return a ProcessNode
     * @throws FlowFrameworkException if the current node does not support updates
     */
    private ProcessNode createUpdateProcessNode(
        WorkflowNode node,
        WorkflowData data,
        List<ProcessNode> predecessorNodes,
        TimeValue nodeTimeout,
        String tenantId
    ) throws FlowFrameworkException {
        String updateStepName = WorkflowResources.getUpdateStepByWorkflowStep(node.type());
        if (updateStepName != null) {
            WorkflowStep step = workflowStepFactory.createStep(updateStepName);
            return new ProcessNode(
                node.id(),
                step,
                node.previousNodeInputs(),
                Collections.emptyMap(), // TODO Add support to reprovision substitution templates
                data,
                predecessorNodes,
                threadPool,
                PROVISION_WORKFLOW_THREAD_POOL,
                nodeTimeout,
                tenantId
            );
        } else {
            // Case 3 : Cannot update step (not supported)
            throw new FlowFrameworkException(
                "Workflow Step " + node.id() + " does not support updates when reprovisioning.",
                RestStatus.BAD_REQUEST
            );
        }
    }

    /**
     * Creates a process node to pass workflow data to the next step in the reprovision sequence
     * @param node the current node
     * @param data the current node data
     * @param predecessorNodes the current node predecessors
     * @param nodeTimeout the current node timeout
     * @param resourcesCreated the list of resources created for the template assoicated with this node
     * @param tenantId the tenant id
     * @return a Process node
     */
    private ProcessNode createWorkflowDataStepNode(
        WorkflowNode node,
        WorkflowData data,
        List<ProcessNode> predecessorNodes,
        TimeValue nodeTimeout,
        List<ResourceCreated> resourcesCreated,
        String tenantId
    ) {
        ResourceCreated nodeResource = resourcesCreated.stream()
            .filter(rc -> rc.workflowStepId().equals(node.id()))
            .findFirst()
            .orElse(null);

        if (nodeResource != null) {
            return new ProcessNode(
                node.id(),
                new WorkflowDataStep(nodeResource),
                node.previousNodeInputs(),
                Collections.emptyMap(),
                data,
                predecessorNodes,
                threadPool,
                PROVISION_WORKFLOW_THREAD_POOL,
                nodeTimeout,
                tenantId
            );
        } else {
            return null;
        }
    }

    private boolean shouldUpdateNode(WorkflowNode node, WorkflowNode originalNode) throws Exception {
        return !node.previousNodeInputs().equals(originalNode.previousNodeInputs())
            || !ParseUtils.userInputsEquals(originalNode.userInputs(), node.userInputs());
    }

    /**
     * Validates inputs and ensures the required plugins are installed for each step in a topologically sorted graph
     * @param processNodes the topologically sorted list of process nodes
     * @param pluginsService the Plugins Service to retrieve installed plugins
     * @throws Exception if validation fails
     */
    public void validate(List<ProcessNode> processNodes, PluginsService pluginsService) throws Exception {
        List<String> installedPlugins = pluginsService.info()
            .getPluginInfos()
            .stream()
            .map(PluginInfo::getName)
            .collect(Collectors.toList());
        validatePluginsInstalled(processNodes, installedPlugins);
        validateGraph(processNodes);
    }

    /**
     * Validates a sorted workflow, determines if each process node's required plugins are currently installed
     * @param processNodes A list of process nodes
     * @param installedPlugins The list of installed plugins
     * @throws Exception on validation failure
     */
    public void validatePluginsInstalled(List<ProcessNode> processNodes, List<String> installedPlugins) throws Exception {
        // Iterate through process nodes in graph
        for (ProcessNode processNode : processNodes) {

            // Retrieve required plugins of this node based on type
            String nodeType = processNode.workflowStep().getName();
            List<String> requiredPlugins = new ArrayList<>(getRequiredPluginsByWorkflowType(nodeType));
            if (!installedPlugins.containsAll(requiredPlugins)) {
                requiredPlugins.removeAll(installedPlugins);
                throw new FlowFrameworkException(
                    "The workflowStep "
                        + processNode.workflowStep().getName()
                        + " requires the following plugins to be installed : "
                        + requiredPlugins.toString(),
                    RestStatus.BAD_REQUEST
                );
            }
        }
    }

    /**
     * Validates a sorted workflow, determines if each process node's user inputs and predecessor outputs match the expected workflow step inputs
     * @param processNodes A list of process nodes
     * @throws Exception on validation failure
     */
    public void validateGraph(List<ProcessNode> processNodes) throws Exception {

        // Iterate through process nodes in graph
        for (ProcessNode processNode : processNodes) {

            // Get predecessor nodes types of this processNode
            List<String> predecessorNodeTypes = processNode.predecessors()
                .stream()
                .map(x -> x.workflowStep().getName())
                .collect(Collectors.toList());

            // Compile a list of outputs from the predecessor nodes based on type
            List<String> predecessorOutputs = predecessorNodeTypes.stream()
                .map(nodeType -> getOutputByWorkflowType(nodeType))
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

            // Retrieve all the user input data from this node
            List<String> currentNodeUserInputs = new ArrayList<>(processNode.input().getContent().keySet());

            // Combine both predecessor outputs and current node user inputs
            List<String> allInputs = Stream.concat(predecessorOutputs.stream(), currentNodeUserInputs.stream())
                .collect(Collectors.toList());

            // Retrieve list of required inputs from the current process node and compare
            List<String> expectedInputs = new ArrayList<>(getInputByWorkflowType(processNode.workflowStep().getName()));

            if (!allInputs.containsAll(expectedInputs)) {
                expectedInputs.removeAll(allInputs);
                throw new FlowFrameworkException(
                    "Invalid workflow, node ["
                        + processNode.id()
                        + "] missing the following required inputs : "
                        + expectedInputs.toString(),
                    RestStatus.BAD_REQUEST
                );
            }
        }
    }

    /**
     * A method for parsing workflow timeout value.
     * The value could be parsed from node NODE_TIMEOUT_FIELD, the timeout field in workflow-step.json,
     * or the default NODE_TIMEOUT_DEFAULT_VALUE
     * @param node the workflow node
     * @return the timeout value
     */
    protected TimeValue parseTimeout(WorkflowNode node) {
        TimeValue nodeTimeoutValue = Optional.ofNullable(getTimeoutByWorkflowType(node.type())).orElse(NODE_TIMEOUT_DEFAULT_VALUE);
        String nodeTimeoutAsString = nodeTimeoutValue.getSeconds() + "s";
        String timeoutValue = (String) node.userInputs().getOrDefault(NODE_TIMEOUT_FIELD, nodeTimeoutAsString);
        String fieldName = String.join(".", node.id(), USER_INPUTS_FIELD, NODE_TIMEOUT_FIELD);
        TimeValue userInputTimeValue = TimeValue.parseTimeValue(timeoutValue, fieldName);

        if (userInputTimeValue.millis() < 0) {
            throw new FlowFrameworkException(
                "Failed to parse timeout value [" + timeoutValue + "] for field [" + fieldName + "]. Must be positive",
                RestStatus.BAD_REQUEST
            );
        }
        return userInputTimeValue;
    }

    private static List<WorkflowNode> topologicalSort(List<WorkflowNode> workflowNodes, List<WorkflowEdge> workflowEdges) {
        // Basic validation
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        for (WorkflowNode node : workflowNodes) {
            if (nodeMap.containsKey(node.id())) {
                throw new FlowFrameworkException("Duplicate node id " + node.id() + ".", RestStatus.BAD_REQUEST);
            }
            nodeMap.put(node.id(), node);
        }
        for (WorkflowEdge edge : workflowEdges) {
            String source = edge.source();
            if (!nodeMap.containsKey(source)) {
                throw new FlowFrameworkException("Edge source " + source + " does not correspond to a node.", RestStatus.BAD_REQUEST);
            }
            String dest = edge.destination();
            if (!nodeMap.containsKey(dest)) {
                throw new FlowFrameworkException("Edge destination " + dest + " does not correspond to a node.", RestStatus.BAD_REQUEST);
            }
            if (source.equals(dest)) {
                throw new FlowFrameworkException("Edge connects node " + source + " to itself.", RestStatus.BAD_REQUEST);
            }
        }

        // Build predecessor and successor maps
        Map<WorkflowNode, Set<WorkflowEdge>> predecessorEdges = new HashMap<>();
        Map<WorkflowNode, Set<WorkflowEdge>> successorEdges = new HashMap<>();
        for (WorkflowEdge edge : workflowEdges) {
            WorkflowNode source = nodeMap.get(edge.source());
            WorkflowNode dest = nodeMap.get(edge.destination());
            predecessorEdges.computeIfAbsent(dest, k -> new HashSet<>()).add(edge);
            successorEdges.computeIfAbsent(source, k -> new HashSet<>()).add(edge);
        }

        // See https://en.wikipedia.org/wiki/Topological_sorting#Kahn's_algorithm
        Set<WorkflowEdge> graph = new HashSet<>(workflowEdges);
        // L <- Empty list that will contain the sorted elements
        List<WorkflowNode> sortedNodes = new ArrayList<>();
        // S <- Set of all nodes with no incoming edge
        Queue<WorkflowNode> sourceNodes = workflowNodes.stream()
            .filter(n -> !predecessorEdges.containsKey(n))
            .collect(ArrayDeque::new, ArrayDeque::add, ArrayDeque::addAll);
        if (sourceNodes.isEmpty()) {
            throw new FlowFrameworkException("No start node detected: all nodes have a predecessor.", RestStatus.BAD_REQUEST);
        }
        logger.debug("Start node(s): {}", sourceNodes);

        // while S is not empty do
        while (!sourceNodes.isEmpty()) {
            // remove a node n from S
            WorkflowNode n = sourceNodes.poll();
            // add n to L
            sortedNodes.add(n);
            // for each node m with an edge e from n to m do
            for (WorkflowEdge e : successorEdges.getOrDefault(n, Collections.emptySet())) {
                WorkflowNode m = nodeMap.get(e.destination());
                // remove edge e from the graph
                graph.remove(e);
                // if m has no other incoming edges then
                if (predecessorEdges.get(m).stream().noneMatch(graph::contains)) {
                    // insert m into S
                    sourceNodes.add(m);
                }
            }
        }
        if (!graph.isEmpty()) {
            throw new FlowFrameworkException("Cycle detected: " + graph, RestStatus.BAD_REQUEST);
        }
        logger.debug("Execution sequence: {}", sortedNodes);
        return sortedNodes;
    }
}
