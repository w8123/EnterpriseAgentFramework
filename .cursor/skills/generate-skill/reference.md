# Generate Skill Reference

## Example Commands

Run from `ai-skill-scanner/`.

### 1. Prepare classpath for the CLI

```powershell
mvn -q dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt" "-Dmdep.pathSeparator=;"
$cp = "target/classes;" + (Get-Content "target/classpath.txt" -Raw)
```

### 2. Scan a Spring MVC controller

```powershell
java -cp "$cp" com.enterprise.ai.skill.scanner.cli.SkillScannerCli scan-controller `
  --source "../ai-text-service/src/main/java/com/enterprise/ai/controller/RetrievalController.java" `
  --project-name "ai-text-retrieval" `
  --base-url "http://localhost:8080" `
  --context-path "/ai" `
  --output "../docs/generated-manifests/ai-text-retrieval.yaml"
```

### 3. Generate the skill module

```powershell
java -cp "$cp" com.enterprise.ai.skill.scanner.cli.SkillScannerCli generate `
  --manifest "../docs/generated-manifests/ai-text-retrieval.yaml" `
  --template-dir "../templates/skill-service" `
  --output-dir "../skill-services/skill-ai-text-retrieval"
```

## Verification Targets

- Scanner tests: `mvn -q -pl ai-skill-scanner test`
- Generated module + agent integration:

```powershell
mvn -q -pl ai-agent-service -am test "-Dtest=GeneratedSkillModuleIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

## Repo-Specific Notes

- `ai-agent-service` currently auto-discovers generated `AiTool` beans through `ToolRegistry`.
- Full AgentScope usage still needs manual bridge methods in `ToolRegistryAdapter`.
- Regenerating the same output directory is safe: the generator now clears its own managed files before writing.
