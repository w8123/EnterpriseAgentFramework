package com.enterprise.ai.controller;

import com.enterprise.ai.config.KnowledgeDependencyInfoContributor;
import com.enterprise.ai.domain.dto.DocumentImportResourceScope;
import com.enterprise.ai.domain.dto.FileInfoVO;
import com.enterprise.ai.internalauth.KnowledgeBizIndexConsoleAuthFilter;
import com.enterprise.ai.service.DocumentImportJobService;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeConsoleCatalogSnapshotTest {
    @Test void concurrentScopeAndContentChangeCannotMixWithTheEarlierScopeRead() throws Exception {
        try (var db = new KnowledgeQueryTestDatabase(List.of("knowledge_base"))) {
            db.jdbc().update("INSERT INTO knowledge_base(id,name,code,status,vector_collection_name,workspace_id,project_code,scope) VALUES (7,'old-content','kb',1,'physical','team','orders','PROJECT')");
            var knowledge = mock(KnowledgeService.class);
            var scopes = mock(DocumentImportJobService.class);
            when(scopes.describeKnowledgeBaseScope("kb")).thenAnswer(call -> new DocumentImportResourceScope("team",
                    db.jdbc().queryForObject("SELECT project_code FROM knowledge_base WHERE id=7", String.class), "PROJECT"));
            when(knowledge.getFilesByKbCode("kb")).thenAnswer(call -> {
                // A different physical connection commits a move after the scope read but before the content read.
                try (var connection = db.jdbc().getDataSource().getConnection(); var update = connection.createStatement()) {
                    update.executeUpdate("UPDATE knowledge_base SET project_code='finance',name='new-private-content' WHERE id=7");
                }
                var file = new FileInfoVO();
                file.setFileName(db.jdbc().queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
                return List.of(file);
            });
            var controller = new KnowledgeConsoleCatalogController(knowledge, scopes, mock(KnowledgeDependencyInfoContributor.class));
            var proxy = new ProxyFactory(controller);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()), new AnnotationTransactionAttributeSource()));
            var transactional = (KnowledgeConsoleCatalogController) proxy.getProxy();
            var request = new MockHttpServletRequest();
            request.setAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_ACTOR_ATTRIBUTE, "7");
            request.setAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_TENANT_ATTRIBUTE, "default");
            var result = transactional.read(request, Map.of("operation", "FILES", "kbCode", "kb")).getData();
            assertEquals("orders", ((DocumentImportResourceScope) result.get("resource")).getProjectCode());
            assertEquals("old-content", ((FileInfoVO) ((List<?>) result.get("data")).get(0)).getFileName());
            assertEquals("finance", db.jdbc().queryForObject("SELECT project_code FROM knowledge_base WHERE id=7", String.class));
            assertEquals("new-private-content", db.jdbc().queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
        }
    }
}
