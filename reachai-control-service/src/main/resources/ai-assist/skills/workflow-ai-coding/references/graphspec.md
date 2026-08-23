# GraphSpec and Patch Rules

## Source of Truth

- Runtime execution reads `GraphSpec` from `runtime_workflow.graph_spec_json`.
- Canvas layout lives in `runtime_workflow.canvas_json` and is kept in sync by patch operations.
- Never treat canvas-only changes as sufficient for runtime behavior.

## Build Materials From Context

Before patching, read:

`GET /api/workflows/{workflowId}/ai-coding/context`

Use these fields:

- `nodeTypes`: supported node types
- `availableModels`: pick `modelInstanceId` for LLM nodes and workflow default model
- `availableTools`: pick `toolName` / `qualifiedName` for TOOL nodes
- `validation`: current draft errors
- `workflow.updatedAt`: patch `baseRevision`

Never invent model ids or tool names when context lists are available.

## Node Types

Read the schema catalog from context `nodeTypes` (`AgentGraphNodeType.catalog()`), then treat `runtimeExecutable`, `publishable`, `studioEnabled`, `aiAuthoringEnabled` and `enabledVariants` as the product-capability authority. A type can be visible while only one variant is open.

Current Runtime-executable node families:

- Entry/input: `USER_INPUT`
- LLM/reasoning: `LLM`
- Branching: `IF_ELSE`, `INTENT_CLASSIFIER`
- Structured extraction: `PARAMETER_EXTRACT`
- Output: `ANSWER`
- Integrations: `TOOL`
- Page automation: `PAGE_ACTION` (PAGE_ASSISTANT only)
- Structured display: `INTERACTION`, currently restricted to `enabledVariants=["PRESENT_OUTPUT"]`

Do not rely on a hard-coded list of open node types. Use the live context flags and never use a variant absent from `enabledVariants`. In particular, blocking interaction variants remain closed even though `INTERACTION/PRESENT_OUTPUT` is executable and publishable.

## Graph Boundaries

`START` and `END` are Studio-only canvas nodes. GraphSpec never stores them as nodes or edge endpoints.

Required shape:

- Create a real entry node, usually `USER_INPUT`.
- Set `graphSpec.entryNodeId` to that node id.
- Put every terminal branch node id in `graphSpec.exitNodeIds`.
- Keep `edges` limited to real GraphSpec nodes.

For branching flows, declare boundaries separately from topology:

```json
{
  "schemaVersion": 2,
  "entryNodeId": "user_input",
  "exitNodeIds": ["query_answer", "clarify_answer"],
  "edges": [
    { "from": "classifier", "to": "query_action", "condition": "route:query_intent" },
    { "from": "classifier", "to": "clarify_answer", "condition": "route:else" }
  ]
}
```

## Node Config Examples

### USER_INPUT

`USER_INPUT` is the designated writer for the reserved `params` namespace.
For a `PAGE_ASSISTANT`, it is not optional: the graph must contain exactly one
`USER_INPUT` node, `entryNodeId` must point to it, `outputAlias` must be
`params`, and every declared field must also appear in `graphSpec.inputSchema`.

Canonical entry node:

```json
{
  "op": "ADD_NODE",
  "node": {
    "id": "user_input",
    "type": "USER_INPUT",
    "name": "User Input",
    "config": {
      "outputAlias": "params",
      "fields": [
        {
          "name": "question",
          "type": "string",
          "required": true,
          "description": "User question",
          "source": "input.message"
        }
      ]
    }
  }
}
```

The matching graph-level schema is:

```json
{
  "type": "object",
  "properties": {
    "question": { "type": "string", "description": "User question" }
  },
  "required": ["question"],
  "additionalProperties": false
}
```

At Runtime this makes `params.question` available to subsequent templates,
parameter extraction and page-action argument mappings. Do not use a bare
`USER_INPUT` node without fields: Studio and Runtime release validation both
reject it for `PAGE_ASSISTANT`.

### LLM

Use a real model instance from `context.availableModels`:

```json
{
  "op": "ADD_NODE",
  "node": {
    "id": "answer",
    "type": "LLM",
    "name": "Answer",
    "config": {
      "modelInstanceId": "<availableModels[0].id>",
      "prompt": "You are a helpful assistant. Answer the user clearly.",
      "userPrompt": "{{ input }}"
    }
  }
}
```

Notes:

- `modelInstanceId` is required unless workflow `defaultModelInstanceId` is already set.
- `prompt` acts as system prompt; `userPrompt` renders templates from runtime state (`input`, `message`, `lastOutput`, etc.).

### IF_ELSE

Route by structured condition groups. Edge `condition` must match the selected group id (`lastRoute`).

```json
{
  "op": "ADD_NODE",
  "node": {
    "id": "judge",
    "type": "IF_ELSE",
    "name": "Judge Metro Topic",
    "config": {
      "conditionGroups": [
        {
          "id": "metro",
          "logic": "AND",
          "conditions": [
            {
              "left": "input",
              "operator": "contains",
              "right": "地铁"
            }
          ]
        }
      ],
      "defaultRoute": "reject"
    }
  }
}
```

Matching edges:

```json
{ "op": "ADD_EDGE", "edge": { "from": "judge", "to": "answer", "condition": "metro" } }
{ "op": "ADD_EDGE", "edge": { "from": "judge", "to": "reject", "condition": "reject" } }
```

Supported operators include: `contains`, `not_contains`, `eq`, `neq`, `empty`, `not_empty`, `gt`, `gte`, `lt`, `lte`.

Condition operands use runtime expressions such as `input`, `params.message`, `lastOutput`, or `nodeOutput.answer`.
Do not wrap condition operands in `{{ }}`; template rendering is for fields such as LLM `userPrompt` or ANSWER `template`.

### INTENT_CLASSIFIER

- `KEYWORD` uses class keywords and does not require a model.
- `LLM` always classifies with the node model or Workflow default model.
- `HYBRID` uses a keyword match first and falls back to the model.
- Every class id and `defaultRoute` should have a matching outgoing edge such as `route:query` and `route:else`.
- Classification writes `route` / `lastRoute` without replacing the preceding business `lastOutput`.

### ANSWER

Fixed response template:

```json
{
  "op": "ADD_NODE",
  "node": {
    "id": "reject",
    "type": "ANSWER",
    "name": "Reject Non-Metro",
    "config": {
      "template": "抱歉，我只能回答与地铁相关的问题。"
    }
  }
}
```

### TOOL

Use a real tool from `context.availableTools`:

```json
{
  "op": "ADD_NODE",
  "node": {
    "id": "lookup",
    "type": "TOOL",
    "name": "Lookup Tool",
    "config": {
      "toolName": "<availableTools[0].name>",
      "args": {}
    }
  }
}
```

Prefer `toolName` or `qualifiedName` that exists in `availableTools`.

### PARAMETER_EXTRACT

Use `extractMode=expression` to project known runtime values without a model, or `extractMode=llm` to parse natural language with the node model / Workflow default model. Declare at least one uniquely named field. Structured results are published as `nodeOutput.<nodeId>.<fieldName>` and keep their number/boolean types for downstream Tool or PAGE_ACTION mappings.

When a detail Tool requires an opaque id but the user only knows a business name, never pass the name into the id field and never invent an id. Build a visible chain:

1. Extract the business name from `params.question`.
2. Call the list/query Tool with that name.
3. Add a `PARAMETER_EXTRACT` node after the query Tool. Use `extractMode=expression` when its declared output schema gives a stable id path; otherwise use `extractMode=llm`, set `inputExpression=nodeOutput.<queryNodeId>`, and use a rendered `userPrompt` containing both `{{ params.question }}` and `{{ nodeOutput.<queryNodeId> }}`. Require exactly one matching `id`; ambiguity or no match must fail instead of guessing.
4. Map `nodeOutput.<idExtractNodeId>.id` into the detail Tool's id argument.

This is the canonical Tool-output-to-Tool-input pattern for “按名称查详情”. It keeps the list call and detail call independently visible in Run/Trace evidence.

### PAGE_ACTION args binding

`PAGE_ACTION` `config.args` values are resolved by `resolveConfiguredMap` / `resolveExpression`, not only Mustache templates.

### INTERACTION / PRESENT_OUTPUT

`PRESENT_OUTPUT` is a non-blocking display node. It does not pause a Workflow and does not accept a user submission. Use it after a structured `PAGE_ACTION` or `TOOL` result so Embed clients receive a formal `ui.requested` event instead of relying on an LLM to turn data into Markdown.

```json
{
  "id": "present_teams",
  "type": "INTERACTION",
  "name": "展示班组列表",
  "config": {
    "interactionType": "PRESENT_OUTPUT",
    "component": "list_card",
    "dataExpression": "nodeOutput.read_table",
    "outputAlias": "presented_teams",
    "behavior": {
      "blocking": false,
      "readonly": true
    },
    "presentation": {
      "mode": "card_only"
    },
    "renderSchema": {
      "titleField": "teamName",
      "itemKey": "id",
      "totalPath": "total",
      "initialVisibleCount": 5,
      "showCount": true
    }
  }
}
```

Rules:

- List/page results use `list_card`; single objects use `output_card`, `card` or `detail`.
- Prefer `dataExpression=nodeOutput.<producerNodeId>` over `lastOutput`; the explicit source is required by business-page AI Coding acceptance.
- Use `card_only` to avoid duplicate Markdown plus card, or `text_and_card` when a short explanation is useful.
- Do not author `COLLECT_INPUT`, `USER_CHOICE`, `CONFIRM_ACTION`, `REVIEW_EDIT` or other pause/resume variants until the live node catalog explicitly lists them in `enabledVariants`.
- A successful debug node is not browser proof. Final acceptance must capture `ui.requested`, the visible card DOM, and the actual business result.

Preferred expression form:

```json
{
  "owner": "nodeOutput.extract_filters.owner",
  "status": "nodeOutput.extract_filters.status"
}
```

`{{ nodeOutput.extract_filters.owner }}` is also supported (rendered via template engine), but prefer the bare expression form above.

Do not hard-code `null` placeholders in setFilters args.

## Patch Operations

### ADD_NODE

Requires `node.id` and supported `node.type`.

Optional fields: `name`, `description`, `ref`, `config`, schemas, retry/errorPolicy.

The canvas node is always projected from GraphSpec. With `layout.autoLayout=true` (default), the full canvas is rearranged; with `false`, existing coordinates are preserved and only nodes without coordinates are placed.

### UPDATE_NODE

Requires `nodeId`.

Provide either:

- `patch` map with partial fields, or
- `node` object (id ignored)

`config` and other nested maps merge deeply into the existing node. Updating `id` is rejected.

### DELETE_NODE

Requires `nodeId`.

Removes connected edges from GraphSpec and canvas.

### ADD_EDGE

Requires `edge.from` and `edge.to`.

Rules:

- both endpoints must be real node ids in the same GraphSpec
- `START` and `END` endpoints are rejected
- duplicate `(from,to,condition)` edges are rejected

Optional: `condition`, `sourceHandle`, `targetHandle`, `priority`, explicit `id`.

For `IF_ELSE`, set `condition` to the route id (`metro`, `reject`, etc.).

### DELETE_EDGE

Requires `edgeId`.

### UPDATE_EDGE

Requires `edgeId` plus a partial `patch`. Use canonical `from` / `to`; changing the edge id is rejected.

### SET_ENTRY_NODE

Requires `entryNodeId`, a node id that already exists.

### SET_EXIT_NODES

Requires `exitNodeIds`, an array of existing terminal node ids. The full list is replaced atomically.

## Validation Modes

Use release validation through AI Coding:

- current draft: `POST .../validate` with `mode=CURRENT`
- proposed graph: `POST .../validate` with `mode=PROPOSED` and `graphSpec`

Patch dry-run also returns `validation` for the proposed graph.

## Walkthrough: Metro-Only Q&A Workflow

Goal: if the user asks a metro-related question, call LLM to answer; otherwise reject.

Assumptions:

- You already created the workflow and read `/context`.
- `availableModels[0].id` is a valid LLM instance id.

Patch preview example:

```json
{
  "dryRun": true,
  "operations": [
    {
      "op": "ADD_NODE",
      "node": {
        "id": "input",
        "type": "USER_INPUT",
        "name": "Input",
        "config": {
          "outputAlias": "params",
          "fields": [
            { "name": "question", "type": "string", "required": true, "source": "input.message" }
          ]
        }
      }
    },
    {
      "op": "ADD_NODE",
      "node": {
        "id": "judge",
        "type": "IF_ELSE",
        "name": "Metro Topic Judge",
        "config": {
          "conditionGroups": [
            {
              "id": "metro",
              "logic": "AND",
              "conditions": [
                { "left": "input", "operator": "contains", "right": "地铁" }
              ]
            }
          ],
          "defaultRoute": "reject"
        }
      }
    },
    {
      "op": "ADD_NODE",
      "node": {
        "id": "answer",
        "type": "LLM",
        "name": "Metro Answer",
        "config": {
          "modelInstanceId": "<availableModels[0].id>",
          "systemPrompt": "你是地铁问答助手，只回答与中国城市地铁相关的问题。",
          "userPrompt": "{{ input }}"
        }
      }
    },
    {
      "op": "ADD_NODE",
      "node": {
        "id": "reject",
        "type": "ANSWER",
        "name": "Reject",
        "config": {
          "template": "抱歉，我只能回答与地铁相关的问题。"
        }
      }
    },
    { "op": "ADD_EDGE", "edge": { "from": "input", "to": "judge" } },
    { "op": "ADD_EDGE", "edge": { "from": "judge", "to": "answer", "condition": "metro" } },
    { "op": "ADD_EDGE", "edge": { "from": "judge", "to": "reject", "condition": "reject" } },
    { "op": "SET_ENTRY_NODE", "entryNodeId": "input" },
    { "op": "SET_EXIT_NODES", "exitNodeIds": ["answer", "reject"] }
  ]
}
```

Suggested debug cases after save:

- metro case: `"广州地铁末班车几点"`
- reject case: `"今天天气怎么样"`

## Common Failure Patterns

- missing `entryNodeId`
- unsupported node type
- dangling edge endpoint
- LLM node without `modelInstanceId` when workflow default model is absent
- invented `modelInstanceId` not present in `availableModels`
- IF_ELSE edge `condition` not matching route id
- PAGE_ACTION node with invalid page/action catalog mapping

Fix graph semantics first, then save.
