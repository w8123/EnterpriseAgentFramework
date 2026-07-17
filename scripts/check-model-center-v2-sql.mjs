#!/usr/bin/env node
/**
 * Static checks for Model Center V2 SQL scripts (initV2 + upgrade).
 * Fails with a concrete assertion message on regression.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const initPath = join(root, 'sql', 'initV2.sql');
const upgradePath = join(root, 'sql', 'upgrade-20260717-agent-supervisor-runops-model-center-v2.sql');

const init = readFileSync(initPath, 'utf8');
const upgrade = readFileSync(upgradePath, 'utf8');

let failed = 0;

function fail(message) {
  console.error(`FAIL: ${message}`);
  failed += 1;
}

function pass(message) {
  console.log(`ok: ${message}`);
}

function stripSqlComments(sql) {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/--.*$/gm, '');
}

function extractTemplateValues(sql, label) {
  const anchor = sql.match(/INSERT(?:\s+IGNORE)?\s+INTO\s+`model_template`/i);
  if (!anchor) {
    fail(`${label}: model_template INSERT not found`);
    return { ids: [], norm: '' };
  }
  const start = sql.indexOf('VALUES', anchor.index);
  const lastTpl = sql.indexOf("('tpl-vllm-qwen3-32b'", start);
  if (lastTpl < 0) {
    fail(`${label}: tpl-vllm-qwen3-32b row not found`);
    return { ids: [], norm: '' };
  }
  const end = sql.indexOf(')', lastTpl);
  if (end < 0) {
    fail(`${label}: could not locate end of last template VALUES row`);
    return { ids: [], norm: '' };
  }
  const payload = sql.slice(start + 'VALUES'.length, end + 1);
  const ids = [...payload.matchAll(/\('(tpl-[^']+)'/g)].map((m) => m[1]);
  const norm = payload.replace(/\s+/g, ' ').trim();
  return { ids, norm };
}

function extractIncompleteBranch(sql) {
  // Do not stop at nested ELSE (e.g. project_code conflict precheck).
  // Bound the branch by its terminal status SELECT.
  const m = sql.match(
    /ELSEIF\s+v_is_incomplete\s*=\s*1\s+THEN([\s\S]*?)SELECT\s+'incomplete V2 repaired to final schema'/i,
  );
  return m ? m[1] : '';
}

const upgradeExecutable = stripSqlComments(upgrade);
const incompleteBranch = extractIncompleteBranch(upgradeExecutable);

// 1) upgrade must not reference IS_GENERATED in executable SQL
if (/\bIS_GENERATED\b/i.test(upgradeExecutable)) {
  fail('upgrade SQL contains IS_GENERATED in executable SQL (not valid on information_schema.COLUMNS in MySQL 5.7/8)');
} else {
  pass('upgrade SQL has no IS_GENERATED in executable SQL');
}

// 2) template upsert: no semicolon between last VALUES row and ON DUPLICATE KEY UPDATE
const upsertMatch = upgrade.match(
  /INSERT\s+INTO\s+`model_template`[\s\S]*?VALUES[\s\S]*?\('tpl-vllm-qwen3-32b'[\s\S]*?\)\s*([\s\S]*?)ON DUPLICATE KEY UPDATE/i,
);
if (!upsertMatch) {
  fail('upgrade SQL: could not locate template INSERT ... ON DUPLICATE KEY UPDATE block');
} else if (upsertMatch[1].includes(';')) {
  fail('upgrade SQL: semicolon appears between last template VALUES row and ON DUPLICATE KEY UPDATE');
} else {
  pass('template VALUES row connects directly to ON DUPLICATE KEY UPDATE');
}

// 3) upsert statement ends with a single terminating semicolon after updated_at assignment
const upsertStmt = upgrade.match(
  /INSERT\s+INTO\s+`model_template`[\s\S]*?ON DUPLICATE KEY UPDATE[\s\S]*?`updated_at`\s*=\s*CURRENT_TIMESTAMP\s*;/i,
);
if (!upsertStmt) {
  fail('upgrade SQL: template upsert does not end with updated_at = CURRENT_TIMESTAMP;');
} else {
  pass('template upsert ends with updated_at = CURRENT_TIMESTAMP;');
}

// 4) both scripts have exactly 31 fixed templates
const initTpl = extractTemplateValues(init, 'initV2.sql');
const upTpl = extractTemplateValues(upgrade, 'upgrade SQL');
if (initTpl.ids.length !== 31) {
  fail(`initV2.sql has ${initTpl.ids.length} tpl-* rows, expected 31`);
} else {
  pass('initV2.sql has 31 fixed templates');
}
if (upTpl.ids.length !== 31) {
  fail(`upgrade SQL has ${upTpl.ids.length} tpl-* rows, expected 31`);
} else {
  pass('upgrade SQL has 31 fixed templates');
}

// 5) VALUES semantic parity
if (initTpl.norm && upTpl.norm) {
  if (initTpl.norm !== upTpl.norm) {
    fail('initV2.sql and upgrade SQL template VALUES differ semantically');
  } else {
    pass('init/upgrade template VALUES are semantically identical');
  }
}

// 6) fillable ADD COLUMN list (all non-core fillable columns except connection_config_json
//    which is the incomplete-V2 recognition prerequisite, and project_scope_key which is rebuilt)
const fillableAdds = [
  'protocol',
  'project_code',
  'default_options_json',
  'params_schema_json',
  'status',
  'last_test_status',
  'last_test_at',
  'last_test_latency_ms',
  'last_test_error',
  'remark',
];
for (const col of fillableAdds) {
  const re = new RegExp(`ADD COLUMN \`${col}\``, 'i');
  if (!re.test(incompleteBranch)) {
    fail(`incomplete V2 branch missing ADD COLUMN \`${col}\``);
  }
}
if (fillableAdds.every((col) => new RegExp(`ADD COLUMN \`${col}\``, 'i').test(incompleteBranch))) {
  pass('incomplete V2 branch adds all fillable columns including remark');
}

// 7) final V2 depends on core column count before v_is_final
const finalAssignOk =
  /SET\s+v_is_final\s*=\s*IF\([\s\S]{0,300}?v_core_cols\s*=\s*7[\s\S]{0,300}?v_required_cols\s*>=\s*12/i
    .test(upgradeExecutable);
if (!finalAssignOk) {
  fail('v_is_final must require v_core_cols = 7 and v_required_cols >= 12 in the same IF(...)');
} else {
  pass('v_is_final requires core_cols=7 and required_cols>=12');
}

const coreBeforeFinal =
  /SELECT COUNT\(\*\) INTO v_core_cols[\s\S]*?SET v_is_final\s*=/i.test(upgradeExecutable);
if (!coreBeforeFinal) {
  fail('v_core_cols must be queried before SET v_is_final');
} else {
  pass('v_core_cols is queried before final V2 classification');
}

if (!/@core_cols\s*=\s*7/.test(upgradeExecutable) || !/@required_cols\s*>=\s*12/.test(upgradeExecutable)) {
  fail('top-level diagnostic @is_final_v2 must require @core_cols=7 and @required_cols>=12');
} else {
  pass('top-level diagnostic final V2 uses core_cols=7 and required_cols>=12');
}

// 8) remark in required list and final assertion
if (!/'remark'/.test(upgradeExecutable)) {
  fail('upgrade SQL missing remark in required-column checks');
} else {
  pass('remark included in required-column structure checks');
}
if (!/v_required_cols\s*<\s*12/.test(incompleteBranch) && !/v_required_cols\s*<\s*12/.test(upgradeExecutable)) {
  fail('final assertion must check required_cols < 12 (incl. remark)');
} else {
  pass('final assertion checks required_cols including remark');
}
if (!/v_core_cols\s*<\s*7/.test(incompleteBranch)) {
  fail('incomplete V2 final assertion must re-check v_core_cols < 7');
} else {
  pass('incomplete V2 final assertion re-checks core columns');
}

// 9) DROP INDEX before DROP COLUMN project_scope_key in incomplete branch
const dropIndexPos = incompleteBranch.search(
  /DROP INDEX\s+`uk_model_instance_name_scope`/i,
);
const dropColumnPos = incompleteBranch.search(
  /DROP COLUMN\s+`project_scope_key`/i,
);
if (dropIndexPos < 0) {
  fail('incomplete V2 branch missing DROP INDEX uk_model_instance_name_scope');
} else if (dropColumnPos < 0) {
  fail('incomplete V2 branch missing DROP COLUMN project_scope_key');
} else if (dropIndexPos > dropColumnPos) {
  fail(
    'scenario ordinary-scope+valid-uk: DROP INDEX must occur before DROP COLUMN project_scope_key '
      + `(dropIndex@${dropIndexPos} > dropColumn@${dropColumnPos})`,
  );
} else {
  pass('DROP INDEX uk_model_instance_name_scope precedes DROP COLUMN project_scope_key');
}

// 10) prior incomplete V2 path: v_scope_generated=0 OR v_uk_valid=0 forces DROP INDEX
//     even when the unique index shape is currently valid
const gate =
  /IF\s+v_scope_generated\s*=\s*0\s+OR\s+v_uk_valid\s*=\s*0\s+THEN[\s\S]*?DROP INDEX\s+`uk_model_instance_name_scope`/i;
if (!gate.test(incompleteBranch)) {
  fail(
    'prior incomplete V2 path missing: IF v_scope_generated=0 OR v_uk_valid=0 THEN DROP INDEX '
      + '(required when ordinary project_scope_key + valid unique index)',
  );
} else {
  pass('ordinary project_scope_key + valid unique index still drops same-named index first');
}

const rebuildGenerated =
  /IF\s+v_scope_generated\s*=\s*0\s+THEN[\s\S]*?DROP COLUMN\s+`project_scope_key`[\s\S]*?ADD COLUMN\s+`project_scope_key`[\s\S]*?STORED/i;
if (!rebuildGenerated.test(incompleteBranch)) {
  fail('when v_scope_generated=0, incomplete branch must DROP + ADD STORED generated project_scope_key');
} else {
  pass('wrong/ordinary project_scope_key is rebuilt as STORED generated column');
}

const readdUk =
  /IF\s+v_uk_valid\s*=\s*0\s+THEN[\s\S]*?ADD UNIQUE KEY\s+`uk_model_instance_name_scope`/i;
if (!readdUk.test(incompleteBranch)) {
  fail('incomplete branch must re-ADD UNIQUE KEY when index is missing/invalid after repair');
} else {
  pass('unique index is re-added after generated-column repair when needed');
}

// 11) unique index detection uses NON_UNIQUE and SEQ_IN_INDEX
if (!/(?:NON_UNIQUE\s*=\s*0|MAX\s*\(\s*NON_UNIQUE\s*\)\s*=\s*0)/i.test(upgradeExecutable)) {
  fail('upgrade SQL missing NON_UNIQUE = 0 in uk_model_instance_name_scope validation');
} else {
  pass('upgrade SQL validates NON_UNIQUE = 0 for uk_model_instance_name_scope');
}
if (!/SEQ_IN_INDEX\s*=\s*1/i.test(upgradeExecutable) || !/SEQ_IN_INDEX\s*=\s*2/i.test(upgradeExecutable)) {
  fail('upgrade SQL missing SEQ_IN_INDEX column order checks for uk_model_instance_name_scope');
} else {
  pass('upgrade SQL validates SEQ_IN_INDEX order (name, project_scope_key)');
}

// generated column detection uses EXTRA + GENERATION_EXPRESSION normalization
if (!/EXTRA\s+LIKE\s+'%STORED GENERATED%'/i.test(upgradeExecutable)) {
  fail('upgrade SQL missing EXTRA STORED GENERATED check for project_scope_key');
} else {
  pass('upgrade SQL uses EXTRA for STORED generated column detection');
}
if (!/GENERATION_EXPRESSION/i.test(upgradeExecutable)) {
  fail('upgrade SQL missing GENERATION_EXPRESSION normalization for project_scope_key');
} else {
  pass('upgrade SQL normalizes GENERATION_EXPRESSION for IFNULL(project_code, empty)');
}

if (!/structure unknown/i.test(upgradeExecutable)) {
  fail('upgrade SQL missing SIGNAL for unknown model_instance structure');
} else {
  pass('upgrade SQL SIGNALs on unknown structure');
}

// 12) consolidated upgrade ordering and migration-backup cleanup
const runtimeRunPos = upgradeExecutable.search(/CREATE TABLE\s+`runtime_run`/i);
const modelTemplatePos = upgradeExecutable.search(/CREATE TABLE IF NOT EXISTS\s+`model_template`/i);
if (runtimeRunPos < 0 || modelTemplatePos < 0 || runtimeRunPos > modelTemplatePos) {
  fail('consolidated upgrade must apply Agent/RunOps before Model Center V2');
} else {
  pass('consolidated upgrade orders Agent/RunOps before Model Center V2');
}

if (/TO\s+`model_instance_v1_bak_20260716`/i.test(upgradeExecutable)) {
  fail('superseded model_instance_v1_bak_20260716 must not be used as a RENAME target');
} else {
  pass('superseded model_instance_v1_bak_20260716 is not retained as a RENAME target');
}

const cleanupAfterCall = /CALL\s+`reachai_model_center_v2_migrate`\s*\(\s*\)\s*;[\s\S]*?DROP TABLE IF EXISTS\s+`model_instance_v1_bak_20260716`\s*;[\s\S]*?DROP TABLE IF EXISTS\s+`model_instance_v1_mig_20260717`\s*;/i;
if (!cleanupAfterCall.test(upgradeExecutable)) {
  fail('successful Model Center migration must remove legacy and transient old-table copies');
} else {
  pass('successful Model Center migration removes legacy and transient old-table copies');
}

if (failed > 0) {
  console.error(`\nmodel center V2 SQL check failed: ${failed} issue(s)`);
  process.exit(1);
}

console.log('\nmodel center V2 SQL check passed');
