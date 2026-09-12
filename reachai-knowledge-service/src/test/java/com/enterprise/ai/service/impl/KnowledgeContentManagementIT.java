package com.enterprise.ai.service.impl;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
class KnowledgeContentManagementIT extends KnowledgeContentManagementTest {
    @Override
    protected boolean useMysql() { return true; }
}
