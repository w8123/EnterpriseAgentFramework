import assert from 'node:assert'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-service-table-ownership.mjs')

function write(root, rel, text) {
  const target = path.join(root, rel)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}

function writeEntity(root, service, packageName, className, tableName) {
  write(root, `${service}/src/main/java/${packageName.split('.').join('/')}/${className}.java`, `
package ${packageName};

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("${tableName}")
class ${className} {
}
`)
}

function writeMapperWithSelect(root, service, packageName, className, sql) {
  write(root, `${service}/src/main/java/${packageName.split('.').join('/')}/${className}.java`, `
package ${packageName};

import org.apache.ibatis.annotations.Select;

interface ${className} {
    @Select("${sql}")
    Long findOne();
}
`)
}

function writeJdbcTemplateDao(root, service, packageName, className, sql) {
  write(root, `${service}/src/main/java/${packageName.split('.').join('/')}/${className}.java`, `
package ${packageName};

import org.springframework.jdbc.core.JdbcTemplate;

class ${className} {
    private final JdbcTemplate jdbcTemplate;

    ${className}(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Long findOne(Long id) {
        return jdbcTemplate.queryForObject("${sql}", Long.class, id);
    }
}
`)
}

function writeXmlMapper(root, service, rel, sql) {
  write(root, `${service}/src/main/resources/${rel}`, `
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="ExampleMapper">
  <select id="findOne" resultType="long">
    ${sql}
  </select>
</mapper>
`)
}

function writeInitSql(root, sql) {
  write(root, 'sql/init.sql', sql)
}

function writeInitV2Sql(root, sql) {
  write(root, 'sql/initV2.sql', sql)
}

const missingDocRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-missing-doc-'))
writeEntity(missingDocRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')

const missingDocResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingDocRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingDocResult.status, 0, missingDocResult.stderr || missingDocResult.stdout)
assert.match(missingDocResult.stderr, /missing service table ownership doc/)

const missingRowRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-missing-row-'))
writeEntity(missingRowRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')
write(missingRowRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
`)

const missingRowResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingRowRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingRowResult.status, 0, missingRowResult.stderr || missingRowResult.stdout)
assert.match(missingRowResult.stderr, /missing ownership row/)
assert.match(missingRowResult.stderr, /control_thing/)

const undocumentedSharedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-undocumented-shared-'))
writeEntity(undocumentedSharedRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'SharedThingEntity', 'shared_thing')
writeEntity(undocumentedSharedRoot, 'reachai-runtime-service', 'com.enterprise.ai.runtime', 'RuntimeSharedThingEntity', 'shared_thing')
write(undocumentedSharedRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`shared_thing\` | \`reachai-control-service\` | - | Missing runtime exception |
`)

const undocumentedSharedResult = spawnSync(process.execPath, [scriptPath], {
  cwd: undocumentedSharedRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(undocumentedSharedResult.status, 0, undocumentedSharedResult.stderr || undocumentedSharedResult.stdout)
assert.match(undocumentedSharedResult.stderr, /undocumented non-owner table access/)
assert.match(undocumentedSharedResult.stderr, /reachai-runtime-service -> shared_thing/)

const allowedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-allowed-'))
writeEntity(allowedRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')
writeEntity(allowedRoot, 'reachai-runtime-service', 'com.enterprise.ai.runtime', 'RuntimeSharedThingEntity', 'shared_thing')
writeEntity(allowedRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'SharedThingEntity', 'shared_thing')
write(allowedRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`control_thing\` | \`reachai-control-service\` | - | Control-only table |
| \`shared_thing\` | \`reachai-control-service\` | \`reachai-runtime-service\` read | Runtime reads the project binding snapshot |
`)

const allowedResult = spawnSync(process.execPath, [scriptPath], {
  cwd: allowedRoot,
  encoding: 'utf8'
})

assert.strictEqual(allowedResult.status, 0, allowedResult.stderr || allowedResult.stdout)

const staleAdditionalAccessRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-stale-additional-'))
writeEntity(staleAdditionalAccessRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')
write(staleAdditionalAccessRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`control_thing\` | \`reachai-control-service\` | \`reachai-runtime-service\` read | Stale exception |
`)

const staleAdditionalAccessResult = spawnSync(process.execPath, [scriptPath], {
  cwd: staleAdditionalAccessRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(staleAdditionalAccessResult.status, 0, staleAdditionalAccessResult.stderr || staleAdditionalAccessResult.stdout)
assert.match(staleAdditionalAccessResult.stderr, /stale additional direct access/)
assert.match(staleAdditionalAccessResult.stderr, /control_thing/)

const crossServiceMapperImportRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-cross-mapper-'))
write(crossServiceMapperImportRoot, 'reachai-control-service/src/main/java/com/enterprise/ai/control/BadControlService.java', `
package com.enterprise.ai.control;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;

class BadControlService {
    private RuntimeWorkflowDefinitionMapper mapper;
}
`)
write(crossServiceMapperImportRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
`)

const crossServiceMapperImportResult = spawnSync(process.execPath, [scriptPath], {
  cwd: crossServiceMapperImportRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(crossServiceMapperImportResult.status, 0, crossServiceMapperImportResult.stderr || crossServiceMapperImportResult.stdout)
assert.match(crossServiceMapperImportResult.stderr, /cross-service Mapper\/Entity import/)
assert.match(crossServiceMapperImportResult.stderr, /reachai-control-service -> reachai-runtime-service/)

const missingInitTableRowRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-missing-init-row-'))
writeEntity(missingInitTableRowRoot, 'reachai-control-service', 'com.enterprise.ai.control', 'ControlThingEntity', 'control_thing')
writeInitV2Sql(missingInitTableRowRoot, `
CREATE TABLE IF NOT EXISTS \`control_thing\` (
  \`id\` BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS \`baseline_only_thing\` (
  \`id\` BIGINT NOT NULL
);
`)
write(missingInitTableRowRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`control_thing\` | \`reachai-control-service\` | - | Control-only table |
`)

const missingInitTableRowResult = spawnSync(process.execPath, [scriptPath], {
  cwd: missingInitTableRowRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(missingInitTableRowResult.status, 0, missingInitTableRowResult.stderr || missingInitTableRowResult.stdout)
assert.match(missingInitTableRowResult.stderr, /missing ownership row/)
assert.match(missingInitTableRowResult.stderr, /baseline_only_thing/)

const initV2PreferredRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-init-v2-'))
writeInitSql(initV2PreferredRoot, `
CREATE TABLE IF NOT EXISTS \`legacy_table\` (
  \`id\` BIGINT NOT NULL
);
`)
writeInitV2Sql(initV2PreferredRoot, `
CREATE TABLE IF NOT EXISTS \`control_v2_table\` (
  \`id\` BIGINT NOT NULL
);
`)
write(initV2PreferredRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`legacy_table\` | \`reachai-control-service\` | - | V1-only table |
`)

const initV2PreferredResult = spawnSync(process.execPath, [scriptPath], {
  cwd: initV2PreferredRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(initV2PreferredResult.status, 0, initV2PreferredResult.stderr || initV2PreferredResult.stdout)
assert.match(initV2PreferredResult.stderr, /missing ownership row/)
assert.match(initV2PreferredResult.stderr, /control_v2_table/)

const annotatedSqlCrossServiceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-annotated-sql-'))
writeEntity(annotatedSqlCrossServiceRoot, 'reachai-runtime-service', 'com.enterprise.ai.runtime', 'RuntimeOwnedEntity', 'runtime_owned')
writeMapperWithSelect(
  annotatedSqlCrossServiceRoot,
  'reachai-control-service',
  'com.enterprise.ai.control',
  'BadControlMapper',
  'SELECT id FROM runtime_owned WHERE id = #{id}'
)
write(annotatedSqlCrossServiceRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`runtime_owned\` | \`reachai-runtime-service\` | - | Runtime-owned table |
`)

const annotatedSqlCrossServiceResult = spawnSync(process.execPath, [scriptPath], {
  cwd: annotatedSqlCrossServiceRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(annotatedSqlCrossServiceResult.status, 0, annotatedSqlCrossServiceResult.stderr || annotatedSqlCrossServiceResult.stdout)
assert.match(annotatedSqlCrossServiceResult.stderr, /undocumented non-owner table access/)
assert.match(annotatedSqlCrossServiceResult.stderr, /reachai-control-service -> runtime_owned/)

const xmlSqlCrossServiceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-xml-sql-'))
writeEntity(xmlSqlCrossServiceRoot, 'reachai-runtime-service', 'com.enterprise.ai.runtime', 'RuntimeXmlOwnedEntity', 'runtime_xml_owned')
writeXmlMapper(xmlSqlCrossServiceRoot, 'reachai-control-service', 'mapper/BadMapper.xml', 'SELECT id FROM runtime_xml_owned WHERE id = #{id}')
write(xmlSqlCrossServiceRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`runtime_xml_owned\` | \`reachai-runtime-service\` | - | Runtime-owned table |
`)

const xmlSqlCrossServiceResult = spawnSync(process.execPath, [scriptPath], {
  cwd: xmlSqlCrossServiceRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(xmlSqlCrossServiceResult.status, 0, xmlSqlCrossServiceResult.stderr || xmlSqlCrossServiceResult.stdout)
assert.match(xmlSqlCrossServiceResult.stderr, /undocumented non-owner table access/)
assert.match(xmlSqlCrossServiceResult.stderr, /reachai-control-service -> runtime_xml_owned/)

const jdbcSqlCrossServiceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-table-owner-jdbc-sql-'))
writeEntity(jdbcSqlCrossServiceRoot, 'reachai-runtime-service', 'com.enterprise.ai.runtime', 'RuntimeJdbcOwnedEntity', 'runtime_jdbc_owned')
writeJdbcTemplateDao(
  jdbcSqlCrossServiceRoot,
  'reachai-control-service',
  'com.enterprise.ai.control',
  'BadControlJdbcDao',
  'SELECT id FROM runtime_jdbc_owned WHERE id = ?'
)
write(jdbcSqlCrossServiceRoot, 'docs/architecture/service-table-ownership.md', `
# Service Table Ownership

| Table | Owner service | Additional direct access | Notes |
| --- | --- | --- | --- |
| \`runtime_jdbc_owned\` | \`reachai-runtime-service\` | - | Runtime-owned table |
`)

const jdbcSqlCrossServiceResult = spawnSync(process.execPath, [scriptPath], {
  cwd: jdbcSqlCrossServiceRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(jdbcSqlCrossServiceResult.status, 0, jdbcSqlCrossServiceResult.stderr || jdbcSqlCrossServiceResult.stdout)
assert.match(jdbcSqlCrossServiceResult.stderr, /undocumented non-owner table access/)
assert.match(jdbcSqlCrossServiceResult.stderr, /reachai-control-service -> runtime_jdbc_owned/)
