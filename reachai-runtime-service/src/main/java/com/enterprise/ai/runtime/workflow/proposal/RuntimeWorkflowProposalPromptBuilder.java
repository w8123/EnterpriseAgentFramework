package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityDescriptor;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds authoring prompts from the node catalog and available resources. */
final class RuntimeWorkflowProposalPromptBuilder {

    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry;
    private final RuntimeWorkflowPageAssistantProposalPolicy pageAssistantPolicy;

    RuntimeWorkflowProposalPromptBuilder(ObjectMapper objectMapper,
                                         RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry,
                                         RuntimeWorkflowPageAssistantProposalPolicy pageAssistantPolicy) {
        this.objectMapper = objectMapper;
        this.nodeCapabilityRegistry = nodeCapabilityRegistry;
        this.pageAssistantPolicy = pageAssistantPolicy;
    }

    String systemPrompt(RuntimeWorkflowProposalGenerationRequest request) {
        List<RuntimeWorkflowNodeCapabilityDescriptor> authorable = nodeCapabilityRegistry.aiAuthoringCatalog();
        String authorableTypes = authorable.stream()
                .map(RuntimeWorkflowNodeCapabilityDescriptor::type)
                .collect(Collectors.joining(", "));
        String closedTypes = nodeCapabilityRegistry.allCatalog().stream()
                .filter(item -> !item.aiAuthoringEnabled())
                .map(RuntimeWorkflowNodeCapabilityDescriptor::type)
                .collect(Collectors.joining(", "));
        StringBuilder prompt = new StringBuilder("""
                You are Workflow Studio's workflow proposal generator. Return only strict JSON.
                Generate a complete workflow proposal from the user's requirement.
                Use only canonical node types listed in nodeTypes. Use only supplied tools/capabilities/knowledgeBases/pageActions when binding real resources.
                Authorable canonical node types (type must match one of these exact values): %s.
                For pageAction nodes, always set config.ref to the exact actionKey from pageActions.
                If a business step has no matching resource, still generate the node and mark it as a placeholder by setting config.needsConfiguration=true and config.placeholderReason.
                Do not output START/END nodes or edges. Set entryNodeId and exitNodeIds explicitly; every edge.from and edge.to must reference a real node id.
                Never invent node kinds outside nodeTypes. Closed / non-authorable types are forbidden: %s.
                INTERACTION is variant-restricted: author it only with config.interactionType=PRESENT_OUTPUT. COLLECT_INPUT, USER_CHOICE, CONFIRM_ACTION, REVIEW_EDIT and other pause/resume variants are forbidden. PRESENT_OUTPUT must be display-only, must set dataExpression to an explicit producer output when possible, and should use presentation.mode=card_only for structured business results.
                Required JSON shape:
                {"summary":"short summary","entryNodeId":"real node id","exitNodeIds":["real node id"],"nodes":[{"id":"stable_snake_case","type":"canonical node type","label":"display name","description":"what it does","config":{},"inputs":[],"outputs":[]}],"edges":[{"id":"optional","from":"real node id","to":"real node id","condition":"always|approved|rejected|route:key|success|error","sourceHandle":"optional","targetHandle":"optional"}],"warnings":[]}
                inputs and outputs must be arrays of port objects like {"id":"portId","name":"portName","type":"any"}, never bare strings.
                Config inputMapping, assignments, args, inputSchema and outputSchema must be JSON objects, never arrays.
                VARIABLE_ASSIGN example: config.assignments={"count":"nodeOutput.dictionary_count"}. An integer tool return is the producer root value; never append .data unless its declared response schema contains that property.
                """.formatted(authorableTypes, closedTypes));
        prompt.append("""

                KNOWLEDGE_RETRIEVAL evidence policy rules:
                - Default config.evidencePolicy to REQUIRED for factual answers grounded in a knowledge base.
                - REQUIRED must have exactly addressable route:evidence and route:no_evidence outgoing paths. Do not use always/success as a fallback from this node.
                - The route:evidence path may synthesize an answer from retrieved hits. The route:no_evidence path must end in a fixed ANSWER that clearly says evidence was not found; it must not ask an LLM to invent an answer.
                - Use OPTIONAL only when the requirement explicitly permits general-model knowledge fallback; OPTIONAL keeps the normal linear edge behavior.
                """);
        if (pageAssistantPolicy.isPageAssistantProposal(request)) {
            prompt.append("""

                    PAGE_ASSISTANT proposal rules (mandatory when pageActions are supplied):
                    - Never invent or assume pageAction actionKeys. Use only actionKeys present in pageActions/allowedActionKeys.
                    - Do not create setFilters unless pageActions contains setFilters. If setFilters is absent, skip the filter-setting step and use the supplied actions only.
                    - First decide whether selected pageActions represent a single linear query flow or multiple mutually exclusive user intents.
                    - flowMode=LINEAR_QUERY: build one linear flow using recommendedFlow order; do NOT create INTENT_CLASSIFIER.
                    - flowMode=INTENT_ROUTER: create INTENT_CLASSIFIER with strategy=HYBRID, inputExpression=input, defaultRoute=else; route each intent branch with condition route:<classId>; connect route:else to ANSWER asking the user to clarify query/reset/page-state/operation intent.
                    - Standard linear query chains (no classifier): setFilters->search->readTable, setFilters->search, search->readTable, or any subset containing only setFilters/search/readTable.
                    - Use INTENT_CLASSIFIER when multiple terminal intents exist, such as search+reset, search+getPageState, query chain plus reset/getPageState/openRowAction/confirmRequired actions, or when uncertain.
                    - Do not linearly chain mutually exclusive actions such as search then reset then getPageState.
                    - When setFilters is available in a query branch, add one LLM extract node before it with outputAlias=extracted_filters, outputFormat=json, structuredOutput=true.
                    - setFilters pageAction config.args must map each inputSchema field to extracted_filters.<fieldName>; never leave args empty when setFilters is selected.
                    - search/readTable/reset/getPageState pageAction nodes usually use empty args unless the action schema requires parameters.
                    - Do not emit blocking INTERACTION or HUMAN_APPROVAL nodes. INTERACTION is allowed only as display-only PRESENT_OUTPUT.
                    - Every branch that returns structured read data must end with INTERACTION/PRESENT_OUTPUT. Use component=list_card for list/page results and component=output_card or detail for a single object. Set dataExpression to nodeOutput.<producerNodeId> (or a declared output alias), behavior.blocking=false and presentation.mode=card_only.
                    - For confirmRequired or operational actions, emit PAGE_ACTION with config.confirm=true. Page Bridge performs pre-execution confirmation before the action runs; if the user rejects, the action must not execute. ANSWER only reports success, rejection, cancellation or failure afterwards — never request confirmation after PAGE_ACTION has already run.
                    - answer node must use a fixed Chinese status sentence, not {{ lastOutput }}, when the flow ends after page actions.
                    - Bind each pageAction config.ref to the exact actionKey from pageActions.

                    Few-shot A (LINEAR_QUERY, no classifier):
                    selectedActionKeys: setFilters, search, readTable
                    USER_INPUT -> LLM(extracted_filters) -> PAGE_ACTION(setFilters) -> PAGE_ACTION(search) -> PAGE_ACTION(readTable) -> INTERACTION(PRESENT_OUTPUT, list_card, dataExpression=nodeOutput.read_table, card_only)

                    Few-shot B (INTENT_ROUTER with HYBRID classifier):
                    selectedActionKeys: search, reset, getPageState
                    USER_INPUT -> INTENT_CLASSIFIER(strategy=HYBRID)
                    route:search_intent -> PAGE_ACTION(search) -> PAGE_ACTION(readTable) -> INTERACTION(PRESENT_OUTPUT, list_card, dataExpression=nodeOutput.read_table, card_only)
                    route:reset_intent -> PAGE_ACTION(reset) -> ANSWER
                    route:page_state_intent -> PAGE_ACTION(getPageState) -> INTERACTION(PRESENT_OUTPUT, output_card, dataExpression=nodeOutput.get_page_state, card_only)
                    route:else -> ANSWER(请说明要查询、重置还是读取页面状态)

                    Few-shot C (INTENT_ROUTER with confirmRequired operational action):
                    selectedActionKeys: readTable, openRowAction(confirmRequired=true)
                    USER_INPUT -> INTENT_CLASSIFIER(strategy=HYBRID)
                    route:read_table_intent -> PAGE_ACTION(readTable) -> INTERACTION(PRESENT_OUTPUT, list_card, dataExpression=nodeOutput.read_table, card_only)
                    route:row_action_intent -> PAGE_ACTION(openRowAction, confirm=true; Page Bridge confirms before execution) -> ANSWER(status only)
                    route:else -> ANSWER
                    """);
        }
        return prompt.toString();
    }

    String userPrompt(RuntimeWorkflowProposalGenerationRequest request) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowId", request.workflowId());
        payload.put("workflowName", request.workflowName());
        payload.put("projectCode", request.projectCode());
        payload.put("workflowKind", request.workflowKind());
        payload.put("requirement", request.requirement());
        payload.put("modelInstanceId", request.modelInstanceId());
        payload.put("nodeTypes", nodeCapabilityRegistry.aiAuthoringCatalog());
        payload.put("tools", request.tools() == null ? List.of() : request.tools());
        payload.put("capabilities", request.capabilities() == null ? List.of() : request.capabilities());
        payload.put("knowledgeBases", request.knowledgeBases() == null ? List.of() : request.knowledgeBases());
        payload.put("pageActions", request.pageActions() == null ? List.of() : request.pageActions());
        if (pageAssistantPolicy.isPageAssistantProposal(request)) {
            payload.put("pageAssistantTemplate", pageAssistantPolicy.promptTemplate(request));
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
    }
}
