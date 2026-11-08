package com.enterprise.ai.runtime.execution.kernel;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Interpreted JavaScript sandbox for the workflow CODE node.
 *
 * <p>Only the safe ECMAScript standard objects exist: no Java packages, no class access (the class shutter
 * denies everything), no I/O, no timers. Data crosses the boundary as JSON text only, so script code never
 * sees a Java object. Each run is bounded by wall time and by an instruction budget; a few builtins that
 * can allocate huge strings or arrays in a single native call are removed.</p>
 */
final class RuntimeCodeSandbox {

    static final long DEFAULT_TIMEOUT_MS = 3_000L;
    static final long MAX_TIMEOUT_MS = 10_000L;
    static final int MAX_CODE_LENGTH = 20_000;
    static final int MAX_INPUT_JSON_LENGTH = 1_000_000;
    static final int MAX_OUTPUT_JSON_LENGTH = 1_000_000;
    private static final long INSTRUCTION_BUDGET = 50_000_000L;
    private static final int OBSERVER_THRESHOLD = 10_000;

    private static final String PRELUDE = String.join("\n",
            "delete String.prototype.repeat;",
            "delete String.prototype.padStart;",
            "delete String.prototype.padEnd;",
            "delete Array.prototype.fill;",
            "var __reachaiRun = function (__code, __inputJson) {",
            "  var main = (new Function(__code + '\\n;return typeof main === \"function\" ? main : null;'))();",
            "  if (typeof main !== 'function') throw new Error('CODE_MAIN_MISSING');",
            "  var result = main(JSON.parse(__inputJson));",
            "  if (result === undefined) result = null;",
            "  return JSON.stringify(result);",
            "};");

    private static final ContextFactory FACTORY = new SandboxContextFactory();

    private RuntimeCodeSandbox() {
    }

    /** Runs {@code main(inputs)} from the script and returns its result as JSON text. */
    static String run(String code, String inputJson, long timeoutMs) {
        if (code == null || code.isBlank()) {
            throw new CodeFailure("RUNTIME_GRAPH_CODE_REQUIRED", "代码为空");
        }
        if (code.length() > MAX_CODE_LENGTH) {
            throw new CodeFailure("RUNTIME_GRAPH_CODE_TOO_LONG", "代码超过 " + MAX_CODE_LENGTH + " 个字符");
        }
        if (inputJson != null && inputJson.length() > MAX_INPUT_JSON_LENGTH) {
            throw new CodeFailure("RUNTIME_GRAPH_CODE_INPUT_TOO_LARGE", "代码节点的输入超过 1MB");
        }
        long limit = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : Math.min(timeoutMs, MAX_TIMEOUT_MS);
        Context cx = FACTORY.enterContext();
        try {
            Budget budget = new Budget(System.currentTimeMillis() + limit);
            cx.putThreadLocal(Budget.class, budget);
            ScriptableObject scope = cx.initSafeStandardObjects(null, true);
            cx.evaluateString(scope, PRELUDE, "prelude", 1, null);
            Object runner = ScriptableObject.getProperty(scope, "__reachaiRun");
            if (!(runner instanceof Function function)) {
                throw new CodeFailure("RUNTIME_GRAPH_CODE_FAILED", "沙箱初始化失败");
            }
            Object result = function.call(cx, scope, scope, new Object[]{code, inputJson == null ? "null" : inputJson});
            String json = result == null || result == Scriptable.NOT_FOUND ? "null" : Context.toString(result);
            if (json.length() > MAX_OUTPUT_JSON_LENGTH) {
                throw new CodeFailure("RUNTIME_GRAPH_CODE_OUTPUT_TOO_LARGE", "代码节点的输出超过 1MB");
            }
            return json;
        } catch (BudgetExceeded ex) {
            throw new CodeFailure("RUNTIME_GRAPH_CODE_TIMEOUT", ex.getMessage());
        } catch (JavaScriptException ex) {
            String message = ex.details();
            if (message != null && message.contains("CODE_MAIN_MISSING")) {
                throw new CodeFailure("RUNTIME_GRAPH_CODE_MAIN_MISSING", "代码里需要定义 function main(inputs) { ... }");
            }
            throw new CodeFailure("RUNTIME_GRAPH_CODE_FAILED", "代码抛出错误：" + trim(message) + location(ex));
        } catch (EvaluatorException ex) {
            throw new CodeFailure("RUNTIME_GRAPH_CODE_SYNTAX", "代码语法错误：" + trim(ex.details()) + location(ex));
        } catch (RhinoException ex) {
            throw new CodeFailure("RUNTIME_GRAPH_CODE_FAILED", "代码运行失败：" + trim(ex.details()) + location(ex));
        } catch (StackOverflowError ex) {
            throw new CodeFailure("RUNTIME_GRAPH_CODE_FAILED", "代码递归太深");
        } finally {
            Context.exit();
        }
    }

    private static String location(RhinoException ex) {
        return ex.lineNumber() > 0 ? "（第 " + ex.lineNumber() + " 行）" : "";
    }

    private static String trim(String value) {
        if (value == null) return "未知错误";
        String text = value.trim();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    static final class CodeFailure extends RuntimeException {
        private final String code;

        CodeFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static final class BudgetExceeded extends Error {
        BudgetExceeded(String message) {
            super(message, null, false, false);
        }
    }

    private static final class Budget {
        private final long deadline;
        private long instructions;

        private Budget(long deadline) {
            this.deadline = deadline;
        }
    }

    private static final class SandboxContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(OBSERVER_THRESHOLD);
            cx.setMaximumInterpreterStackDepth(500);
            cx.setClassShutter(className -> false);
            return cx;
        }

        @Override
        protected boolean hasFeature(Context cx, int featureIndex) {
            if (featureIndex == Context.FEATURE_ENHANCED_JAVA_ACCESS) return false;
            return super.hasFeature(cx, featureIndex);
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object value = cx.getThreadLocal(Budget.class);
            if (!(value instanceof Budget budget)) return;
            budget.instructions += instructionCount;
            if (budget.instructions > INSTRUCTION_BUDGET) {
                throw new BudgetExceeded("代码运行步数超过上限，可能有死循环");
            }
            if (System.currentTimeMillis() > budget.deadline) {
                throw new BudgetExceeded("代码运行超时");
            }
        }
    }
}
