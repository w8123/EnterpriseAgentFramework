-- Capability owner only. Add one immutable official profile; retain v1 and every existing binding/provider/credential.
-- Source: https://raw.githubusercontent.com/open-meteo/open-meteo/930063f12047363861f775c685c16a98b52dbfe4/openapi/forecast.yml
-- Raw official bytes SHA256: 512e29bca2afb06a088cc27fae5c6793a05e272c6404a67e3632cecb87f8516f
-- Normalized curated OpenAPI snapshot SHA256: 570d8f8258ee4b9d187cb9d2f3416ef098f75c2320c1406f0eee1600781f51fc
-- No schema changes, no outbound HTTP, no historical success proof or implicit project selection.
INSERT INTO capability_external_api_version
  (entry_id, version_key, base_url, openapi_url, spec_hash, raw_metadata_json, publication_status, published_at)
SELECT e.id, 'official-930063f12047-scalar-query-20261009', 'https://api.open-meteo.com', 'https://raw.githubusercontent.com/open-meteo/open-meteo/930063f12047363861f775c685c16a98b52dbfe4/openapi/forecast.yml', '570d8f8258ee4b9d187cb9d2f3416ef098f75c2320c1406f0eee1600781f51fc', '{"sourceType":"OFFICIAL_OPENAPI_CURATED_PROFILE","sourceUrl":"https://raw.githubusercontent.com/open-meteo/open-meteo/930063f12047363861f775c685c16a98b52dbfe4/openapi/forecast.yml","sourceRevision":"930063f12047363861f775c685c16a98b52dbfe4","officialSpecByteSHA256":"512e29bca2afb06a088cc27fae5c6793a05e272c6404a67e3632cecb87f8516f","sourceSnapshotPath":"docs/integrations/api-market/open-meteo-forecast-scalar-20261009.json","specHash":"570d8f8258ee4b9d187cb9d2f3416ef098f75c2320c1406f0eee1600781f51fc","profile":"SCALAR_COORDINATES_ONLY","requestParameterSubset":["latitude","longitude"],"responseProjection":"Documented root metadata; additional properties allowed","oldVersionRetained":"v1","oldBindingsChanged":false}', 'PUBLISHED', '2026-10-09 12:04:03'
FROM capability_external_api_entry e
WHERE e.entry_key='open-meteo' AND e.auth_type='NONE' AND e.publication_status='PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM capability_external_api_version v WHERE v.entry_id=e.id AND v.version_key='official-930063f12047-scalar-query-20261009');

INSERT INTO capability_external_api_operation
  (version_id, operation_key, operation_id, title, description, http_method, path, side_effect, auth_required,
   request_schema_json, response_schema_json, response_content_type, response_status, example_params_json, status)
SELECT v.id, 'forecast-coordinates', 'getForecastCoordinates', '官方天气位置元数据查询（标量参数）',
  '官方 Forecast GET 的明确标量子集；仅 latitude/longitude，不请求数组天气变量，不含凭据。',
  'GET', '/v1/forecast', 'READ_ONLY', 0, '{"type":"object","properties":{"latitude":{"type":"string","location":"query"},"longitude":{"type":"string","location":"query"}},"required":["latitude","longitude"],"additionalProperties":false}', '{"type":"object","properties":{"latitude":{"type":"number","format":"float"},"longitude":{"type":"number","format":"float"},"elevation":{"type":"number","format":"float"},"generationtime_ms":{"type":"number","format":"float"},"utc_offset_seconds":{"type":"integer"},"timezone":{"type":"string"},"timezone_abbreviation":{"type":"string"}},"additionalProperties":true}',
  'application/json', 200, '{"latitude":"52.52","longitude":"13.41"}', 'ACTIVE'
FROM capability_external_api_version v JOIN capability_external_api_entry e ON e.id=v.entry_id
WHERE e.entry_key='open-meteo' AND v.version_key='official-930063f12047-scalar-query-20261009' AND v.spec_hash='570d8f8258ee4b9d187cb9d2f3416ef098f75c2320c1406f0eee1600781f51fc'
  AND NOT EXISTS (SELECT 1 FROM capability_external_api_operation o WHERE o.version_id=v.id AND o.operation_key='forecast-coordinates');
