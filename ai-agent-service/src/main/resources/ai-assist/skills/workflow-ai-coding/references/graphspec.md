# GraphSpec and Patch Rules

## Source of Truth

- Runtime execution reads `GraphSpec` from `ai_workflow.graph_spec_json`.
- Canvas layout lives in `ai_workflow.canvas_json` and is kept in sync by patch operations.
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

Read the authoritative catalog from context `nodeTypes` (`AgentGraphNodeType.catalog()`). Only use supported node types.

Common node families:

- Entry/input: `USER_INPUT`
- LLM/reasoning: `LLM`
- Branching: `IF_ELSE`
- Output: `ANSWER`
- Integrations: `HTTP_REQUEST`, `TOOL`, `CAPABILITY`, `MCP_CALL`, `KNOWLEDGE_WRITE`
- Page automation: `PAGE_ACTION` (PAGE_ASSISTANT only)

## START / END Endpoints

`START` and `END` are virtual GraphSpec edge endpoints. They do not appear in `nodeTypes` and must not be added to `nodes`.

Required shape:

- Create a real entry node, usually `USER_INPUT`.
- Set `graphSpec.entry` to the entry node id.
- Add an edge `START -> <entryNodeId>` with `condition=always`.
- Connect every terminal branch to `END`.
- Do not create ordinary nodes with `id=START`, `id=END`, `type=START`, or `type=END`.

For branching flows, every route must end at `END`:

```json
[
  { "from": "START", "to": "user_input", "condition": "always" },
  { "from": "classifier", "to": "query_action", "condition": "route:query_intent" },
  { "from": "query_answer", "to": "END", "condition": "always" },
  { "from": "classifier", "to": "clarify_answer", "condition": "route:else" },
  { "from": "clarify_answer", "to": "END", "condition": "always" }
]
```

## Node Config Examples

### USER_INPUT

Minimal entry node:

```json
{
  "op": "ADD_NODE",
  "node": {
    "id": "start",
    "type": "USER_INPUT",
    "name": "User Input"
  }
}
```

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

Use this node when extracting query/filter parameters from natural language. For Page Assistant query filters, default to LLM extraction.

```json
{
  "op": "ADD_NODE",
  "node": {
    "id": "extract_filters",
    "type": "PARAMETER_EXTRACT",
    "name": "Extract filters",
    "config": {
      "extractMode": "llm",
      "modelInstanceId": "<ACTIVE_LLM_MODEL_ID>",
      "fields": []
    }
  }
}
```

If the workflow has `defaultModelInstanceId`, use that as `modelInstanceId`; otherwise pick an ACTIVE LLM from `context.availableModels`.

## Patch Operations

### ADD_NODE

Requires `node.id` and supported `node.type`.

Optional fields: `name`, `description`, `ref`, `config`, schemas, retry/errorPolicy.

Canvas node is auto-created when `layout.autoLayout=true`.

### UPDATE_NODE

Requires `nodeId`.

Provide either:

- `patch` map with partial fields, or
- `node` object (id ignored)

`config` merges shallowly into existing config.

### DELETE_NODE

Requires `nodeId`.

Removes connected edges from GraphSpec and canvas.

### ADD_EDGE

Requires `edge.from` and `edge.to`.

Rules:

- `edge.to` cannot be `START`
- `edge.from` cannot be `END`
- endpoints must exist unless they are `START`/`END`
- duplicate `(from,to,condition)` edges are rejected

Optional: `condition`, `sourceHandle`, `targetHandle`, `priority`, explicit `id`.

For `IF_ELSE`, set `condition` to the route id (`metro`, `reject`, etc.).

### DELETE_EDGE

Requires `edgeId`.

### SET_ENTRY

Requires `entry` node id that already exists.

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
      "node": { "id": "start", "type": "USER_INPUT", "name": "Start" }
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
          "prompt": "你是地铁问答助手，只回答与中国城市地铁相关的问题。",
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
    { "op": "ADD_EDGE", "edge": { "from": "start", "to": "judge" } },
    { "op": "ADD_EDGE", "edge": { "from": "judge", "to": "answer", "condition": "metro" } },
    { "op": "ADD_EDGE", "edge": { "from": "judge", "to": "reject", "condition": "reject" } },
    { "op": "SET_ENTRY", "entry": "start" }
  ]
}
```

Suggested debug cases after save:

- metro case: `"广州地铁末班车几点"`
- reject case: `"今天天气怎么样"`

## Common Failure Patterns

- missing entry node
- unsupported node type
- dangling edge endpoint
- LLM node without `modelInstanceId` when workflow default model is absent
- invented `modelInstanceId` not present in `availableModels`
- IF_ELSE edge `condition` not matching route id
- PAGE_ACTION node with invalid page/action catalog mapping

Fix graph semantics first, then save.
