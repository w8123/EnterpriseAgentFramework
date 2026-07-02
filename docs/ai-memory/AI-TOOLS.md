# AI Tooling

This project expects AI coding agents to use MCP tools when they need browser UI checks or live database inspection.

## Browser Debugging

Use Playwright MCP when a task requires browser interaction, screenshots, DOM snapshots, console messages, network inspection, or visual verification.

Recommended prompts:

```text
Use Playwright to open http://localhost:5173, take a screenshot, and verify the sidebar, project selector, and main content are visible.
```

```text
Use Playwright to reproduce the reported UI issue. Capture a screenshot and summarize the console/network errors before changing code.
```

For local frontend work, start the dev server from `ai-admin-front/` first, then use Playwright against the local URL.

## Database Inspection

Use the `dbhub_ai_mysql` MCP server when a task requires live MySQL schema or data inspection.

The DBHub configuration is intentionally read-only:

- `execute_sql` has `readonly = true`.
- `execute_sql` is capped at `max_rows = 500`.
- Source query timeout is `30` seconds.

Use it for:

- Checking whether tables, columns, or indexes exist in `ai_text_service`.
- Comparing live schema state with `sql/init.sql` and any current `sql/upgrade-*.sql` created for an active database change.
- Inspecting small reference rows needed to diagnose UI or backend behavior.

Do not use it for:

- `INSERT`, `UPDATE`, `DELETE`, `ALTER`, `DROP`, `CREATE`, or migration execution.
- Bulk data exports.
- Reading secrets, tokens, user private data, or unrelated business records.

Recommended prompts:

```text
Use dbhub_ai_mysql to check whether agent_workflow_credential and agent_release_event exist. Only run read-only schema queries.
```

```text
Use dbhub_ai_mysql to inspect the columns and indexes for api_graph_edge, then compare the result with sql/init.sql.
```

## Required Local Setup

Cursor can load local MCP config from `.cursor/mcp.json`, and DBHub can read `.cursor/dbhub-ai-mysql.toml`. These files are machine-local and ignored by Git; keep only environment-variable based examples in shared docs. Codex usually uses user-level MCP configuration, so mirror the same server settings there when needed.

Install the MCP packages:

```powershell
npm install -g @bytebase/dbhub@latest
npx -y @playwright/mcp@latest --version
```

Set the database environment variables on each machine. Do not commit real passwords.

```powershell
[Environment]::SetEnvironmentVariable('AI_MYSQL_HOST', 'your-mysql-host', 'User')
[Environment]::SetEnvironmentVariable('AI_MYSQL_PORT', '33106', 'User')
[Environment]::SetEnvironmentVariable('AI_MYSQL_DATABASE', 'ai_text_service', 'User')
[Environment]::SetEnvironmentVariable('AI_MYSQL_USER', 'your-readonly-user', 'User')
[Environment]::SetEnvironmentVariable('AI_MYSQL_PASSWORD', 'your-readonly-password', 'User')
```

Restart Cursor or Codex after changing user environment variables.

For Codex, add equivalent MCP entries to the user Codex config:

```toml
[mcp_servers.playwright]
command = 'C:\Program Files\nodejs\npx.cmd'
args = ['-y', '@playwright/mcp@latest', '--browser', 'msedge', '--caps', 'vision,devtools', '--output-dir', '.cursor/playwright-output', '--output-mode', 'file']
startup_timeout_sec = 120

[mcp_servers.dbhub_ai_mysql]
command = 'C:\Program Files\nodejs\dbhub.cmd'
args = ['--transport', 'stdio', '--config', '.cursor/dbhub-ai-mysql.toml']
startup_timeout_sec = 120
```

If a machine is not Windows, replace `npx.cmd` and `dbhub.cmd` with the local `npx` and `dbhub` executables.
