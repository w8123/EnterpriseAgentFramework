package com.enterprise.ai.capability.catalog.enrichment;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.*;
import com.enterprise.ai.capability.catalog.businessmethod.*;
import com.enterprise.ai.capability.catalog.httpapi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogSubjectReferencePersistenceTest {
    @Test void apiBodyReadsOnlyMatchingRoutesAndKeepsScannerAmbiguityScopeAndCaseRules() throws Exception {
        var fixture = fixture(); var jdbc = fixture.jdbc(); var resolver = fixture.resolver();
        jdbc.update("INSERT INTO capability_http_api_asset(id,project_id,project_code,environment,http_method,route_template,qualified_name) VALUES(21,7,'orders','default','GET','/api/orders/{id}','http-api:orders:21'),(22,7,'orders','default','GET','/other','http-api:orders:22')");
        jdbc.update("INSERT INTO capability_http_api_source_binding(id,asset_id,project_id,project_code,environment,source_kind,source_location,status) VALUES(31,21,7,'orders','default','CONTROLLER_SCAN','src/main/java/example/OrderController.java#example.OrderController#find()','DISCOVERED'),(32,22,7,'orders','default','CONTROLLER_SCAN','Other.java#Other#find()','DISCOVERED')");
        jdbc.update("INSERT INTO capability_scan_project_tool(id,project_id,asset_type,name,source,source_location,http_method,context_path,endpoint_path,parameters_json) VALUES(41,7,'HTTP_API','find','scanner','OrderController.java#OrderController#find',' get ',' /api/// ',' /orders/{id}/// ','not-json'),(42,7,'HTTP_API','other','scanner','Other.java#Other#find','GET',NULL,'/other','not-json'),(43,8,'HTTP_API','foreign','scanner','OrderController.java#OrderController#find','GET','/api','/orders/{id}','not-json'),(44,7,'HTTP_API','case','scanner','OrderController.java#OrderController#find','GET','/api','/Orders/{id}','not-json')");
        jdbc.update("UPDATE capability_scan_project_tool SET http_method=? WHERE id=41", "\t get \r\n");
        var subject = resolver.resolveDocumentReference(7L, "HTTP_API", 21L);
        assertEquals(List.of(41L), subject.observations().stream().map(ScanProjectToolEntity::getId).toList());
        verify(fixture.apis()).selectOne(argThat(q -> q.getSqlSelect().contains("accepted_contract_hash")
                && !q.getSqlSelect().contains("accepted_contract_json") && !q.getSqlSelect().contains("mapping_conditions_json")));
        verify(fixture.sources()).selectList(argThat(q -> q.getSqlSegment().contains("REGEXP") && !q.getSqlSelect().contains("parameters_json")));
        verify(fixture.apis()).selectList(argThat(q -> q.getSqlSegment().contains("route_template =")));
        verify(fixture.bindings()).selectList(argThat(q -> q.getSqlSegment().contains("asset_id IN") && !q.getSqlSelect().contains("source_contract_json")));
        jdbc.update("INSERT INTO capability_http_api_source_binding(id,asset_id,project_id,project_code,environment,source_kind,source_location,status) VALUES(33,21,7,'orders','default','CONTROLLER_SCAN','src/main/java/example/OrderController.java#example.OrderController#find(java.lang.String)','DISCOVERED')");
        assertTrue(resolver.resolveDocumentReference(7L, "HTTP_API", 21L).observations().isEmpty(), "same-owner overload ambiguity must not become a false unique owner");
        assertEquals("SOURCE_OBSERVATION", resolver.resolveDocumentReference(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        jdbc.update("UPDATE capability_http_api_source_binding SET status='REMOVED' WHERE id=33");
        assertEquals("HTTP_API", resolver.resolveDocumentReference(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        jdbc.update("UPDATE capability_http_api_source_binding SET project_code='Orders' WHERE id=31");
        assertEquals("SOURCE_OBSERVATION", resolver.resolveDocumentReference(7L, "SOURCE_OBSERVATION", 41L).subjectType());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveDocumentReference(7L, "SOURCE_OBSERVATION", 43L));
        verifyNoInteractions(fixture.methodStore(), fixture.apiCatalog());
    }

    @Test void indexBatchesOnlyRequestedDocumentsAndCanonicalSourcesWithoutContractsOrPerDocumentReads() throws Exception {
        var fixture = fixture(); var jdbc = fixture.jdbc(); var resolver = fixture.resolver();
        for (int i=1; i<=150; i++) {
            jdbc.update("INSERT INTO capability_business_method_asset(id,project_id,qualified_name,title,accepted_revision_id,status) VALUES(?,7,?,?,?,'ACCEPTED')",i,"orders:method"+i,"method "+i,i);
            jdbc.update("INSERT INTO capability_scan_project_tool(id,project_id,asset_type,source_qualified_name,title,parameters_json) VALUES(?,7,'BUSINESS_METHOD',?,?,'not-json')",1000+i,"orders:method"+i,"source "+i);
        }
        var refs = new ArrayList<CatalogSubjectResolver.DocumentReference>();
        for (long id=1; id<=100; id++) refs.add(new CatalogSubjectResolver.DocumentReference("BUSINESS_METHOD",id));
        refs.add(new CatalogSubjectResolver.DocumentReference("SOURCE_OBSERVATION",1101L));
        var result = resolver.documentReferences(7L,refs);
        assertEquals(101,result.size());
        assertEquals(List.of(1001L),result.get(refs.get(0)).observations().stream().map(ScanProjectToolEntity::getId).toList());
        var canonical = result.get(refs.get(100)); assertEquals("BUSINESS_METHOD",canonical.subjectType()); assertEquals(101L,canonical.subjectId());
        verify(fixture.methods()).selectList(argThat(q -> q.getSqlSegment().contains("id IN") && q.getSqlSegment().contains("qualified_name IN")));
        verify(fixture.sources(),times(2)).selectList(any());
        verifyNoInteractions(fixture.apis(),fixture.bindings(),fixture.methodStore(),fixture.apiCatalog());
        clearInvocations(fixture.sources(),fixture.methods());
        assertEquals("Orders",resolver.documentReferences(7L,List.of(new CatalogSubjectResolver.DocumentReference("PROJECT",7L))).values().iterator().next().title());
        verifyNoInteractions(fixture.sources(),fixture.methods(),fixture.apis(),fixture.bindings());
    }

    private record Fixture(JdbcTemplate jdbc, CatalogSubjectResolver resolver, ScanProjectToolMapper sources,
            BusinessMethodAssetMapper methods, HttpApiAssetMapper apis, HttpApiSourceBindingMapper bindings,
            BusinessMethodAssetStore methodStore, HttpApiCatalogService apiCatalog) { }
    private Fixture fixture() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:subject_reference_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var jdbc = new JdbcTemplate(ds);
        createTable(jdbc,"capability_scan_project",ScanProjectEntity.class);
        createTable(jdbc,"capability_scan_project_tool",ScanProjectToolEntity.class);
        createTable(jdbc,"capability_business_method_asset",BusinessMethodAssetEntity.class);
        createTable(jdbc,"capability_http_api_asset",HttpApiAssetEntity.class);
        createTable(jdbc,"capability_http_api_source_binding",HttpApiSourceBindingEntity.class);
        jdbc.update("INSERT INTO capability_scan_project(id,project_code,name) VALUES(7,'orders','Orders'),(8,'other','Other')");
        var configuration = new MybatisConfiguration();
        for (var mapper : List.of(ScanProjectMapper.class,ScanProjectToolMapper.class,BusinessMethodAssetMapper.class,HttpApiAssetMapper.class,HttpApiSourceBindingMapper.class)) configuration.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        var sources = spy(session.getMapper(ScanProjectToolMapper.class)); var methods = spy(session.getMapper(BusinessMethodAssetMapper.class));
        var apis = spy(session.getMapper(HttpApiAssetMapper.class)); var bindings = spy(session.getMapper(HttpApiSourceBindingMapper.class));
        var methodStore = mock(BusinessMethodAssetStore.class); var apiCatalog = mock(HttpApiCatalogService.class);
        return new Fixture(jdbc,new CatalogSubjectResolver(session.getMapper(ScanProjectMapper.class),sources,methods,methodStore,apis,bindings,apiCatalog,mock(CapabilityCatalogGroupingService.class),new ObjectMapper()),sources,methods,apis,bindings,methodStore,apiCatalog);
    }
    private void createTable(JdbcTemplate jdbc,String name,Class<?> entity) {
        var columns = new ArrayList<String>();
        for (var field : entity.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            String column=field.getName().replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);
            String type=field.getType()==Long.class?"BIGINT":field.getType()==Integer.class?"INT":field.getType()==Boolean.class?"BOOLEAN":field.getType()==java.time.LocalDateTime.class?"TIMESTAMP":"VARCHAR(4096)";
            columns.add(column+" "+type+(column.equals("id")?" PRIMARY KEY":""));
        }
        jdbc.execute("CREATE TABLE "+name+"("+String.join(",",columns)+")");
    }
}
