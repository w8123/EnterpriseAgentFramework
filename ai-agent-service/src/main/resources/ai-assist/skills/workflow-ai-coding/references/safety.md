# Workflow AI Coding Safety

## Publish Boundary

Workflow AI Coding intentionally does **not** expose publish.

Allowed:

- create draft workflow
- patch draft graph
- validate draft
- debug run
- inspect versions and release readiness

Not allowed for AI tools:

- `POST /api/workflows/{workflowId}/versions/publish`
- any rollback or production rollout action

When `/versions` reports `releaseValidation.valid=true`, tell the human operator to publish manually in admin UI.

## Optimistic Locking

Always include current `workflow.updatedAt` as `baseRevision` when saving patches.

If save fails with revision mismatch:

1. re-read `/context`
2. re-apply intended operations against latest draft
3. validate
4. save again

Never blindly overwrite another editor's changes.

## Side Effects

These node types can mutate external systems or data:

- `HTTP_REQUEST`
- `TOOL`
- `CAPABILITY`
- `MCP_CALL`
- `KNOWLEDGE_WRITE`

Before real execution, require:

```json
{
  "runtimeContext": {
    "confirmSideEffects": true
  }
}
```

Prefer `dryRun=true` first.

## PAGE_ACTION Safety

`PAGE_ACTION` nodes queue client-side actions. Real page execution requires embed/page bridge context and may still need human confirmation for `confirmRequired` actions.

Do not claim a PAGE_ASSISTANT workflow is production-ready based only on backend smoke-test `READY_TO_QUEUE`.

## Secrets and Credentials

- Do not embed secrets, tokens, or raw credentials in GraphSpec node config.
- Reference workflow credentials or platform-managed secret bindings when needed.
- Do not paste platform app secrets into prompts, patches, or skill examples.

## Audit Trail

Patch saves and workflow creates are audited via guard decision logs with action codes such as:

- `CREATE`
- `PATCH_SAVE`

Include a short `reason` when making material changes.

## Error Handling

- `400`: invalid request, validation failure, revision mismatch
- `403`: project permission denied
- `404`: workflow or trace not found

Treat `"trace not found for workflow"` on run detail as cross-workflow access denial, not as invitation to retry with another workflow id.
