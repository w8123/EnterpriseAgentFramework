package com.enterprise.ai.service.impl;

import com.enterprise.ai.repository.UserFilePermissionRepository;
import com.enterprise.ai.security.impl.PermissionServiceImpl;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeFileAuthorizationTest {
    @Test
    void theSameServiceObservesACommittedGrantAndItsRevocation() throws Exception {
        try (var db = new KnowledgeQueryTestDatabase(List.of("knowledge_user_file_permission"), UserFilePermissionRepository.class)) {
            var service = new PermissionServiceImpl(db.mapper(UserFilePermissionRepository.class), new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.jdbc().getDataSource()));
            db.jdbc().update("INSERT INTO knowledge_user_file_permission(user_id,file_id) VALUES ('actor','recreated-file')");
            assertEquals(List.of("recreated-file"), service.getAccessibleFileIds("actor"));
            db.jdbc().update("DELETE FROM knowledge_user_file_permission WHERE user_id='actor'");
            assertEquals(List.of(), service.getAccessibleFileIds("actor"));
        }
    }
}
