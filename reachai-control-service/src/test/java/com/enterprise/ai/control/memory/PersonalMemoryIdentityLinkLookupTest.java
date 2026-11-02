package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalMemoryIdentityLinkLookupTest {
    private JdbcTemplate jdbc;
    private PersonalMemoryIdentityLinkLookup lookup;
    private final PersonalMemoryPrincipal source = PersonalMemoryPrincipal.business("tenant", "Alice", "erp");

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:memory_link_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE control_business_user (id BIGINT PRIMARY KEY, tenant_id VARCHAR_IGNORECASE(96), status VARCHAR(24))");
        jdbc.execute("CREATE TABLE control_external_user_binding (id BIGINT PRIMARY KEY, tenant_id VARCHAR_IGNORECASE(96), business_user_id BIGINT, app_id VARCHAR_IGNORECASE(96), external_user_id VARCHAR_IGNORECASE(128), status VARCHAR(24))");
        jdbc.execute("CREATE TABLE control_personal_memory_identity_link (id BIGINT PRIMARY KEY, tenant_id VARCHAR_IGNORECASE(96), left_binding_id BIGINT, right_binding_id BIGINT, left_business_user_id BIGINT, right_business_user_id BIGINT, left_app_id VARCHAR(96), left_external_user_id VARCHAR(128), right_app_id VARCHAR(96), right_external_user_id VARCHAR(128), state VARCHAR_IGNORECASE(16), active_marker TINYINT, UNIQUE(left_binding_id,right_binding_id,active_marker))");
        jdbc.update("INSERT INTO control_business_user VALUES (1,'tenant','ACTIVE'),(2,'tenant','ACTIVE')");
        jdbc.update("INSERT INTO control_external_user_binding VALUES (11,'tenant',1,'erp','Alice','ACTIVE'),(12,'tenant',2,'crm','bob','ACTIVE')");
        jdbc.update("INSERT INTO control_personal_memory_identity_link VALUES (5,'tenant',11,12,1,2,'erp','Alice','crm','bob','ACTIVE',1)");
        lookup = new PersonalMemoryIdentityLinkLookup(jdbc);
    }

    @Test void confirmedLinkIsBidirectionalButDoesNotLinkLegacyOrCaseDistinctOwners() {
        var peers = lookup.linkedOwners(source);
        assertEquals(1, peers.size());
        assertEquals(PersonalMemoryPrincipal.business("tenant", "bob", "crm"), peers.get(0).owner());
        assertEquals(source, lookup.linkedOwners(peers.get(0).owner()).get(0).owner());
        assertTrue(lookup.linkedOwners(PersonalMemoryPrincipal.business("tenant", "alice", "erp")).isEmpty());
        assertTrue(lookup.linkedOwners(new PersonalMemoryPrincipal("tenant", "Alice", null, null, null)).isEmpty());
    }

    @Test void revocationAndIdentityOrOwnerChangesImmediatelyInvalidateLink() {
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='REVOKED' WHERE id=5");
        assertTrue(lookup.linkedOwners(source).isEmpty());
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='ACTIVE' WHERE id=5");
        jdbc.update("UPDATE control_external_user_binding SET external_user_id='BOB' WHERE id=12");
        assertTrue(lookup.linkedOwners(source).isEmpty());
        jdbc.update("UPDATE control_external_user_binding SET external_user_id='bob', business_user_id=1 WHERE id=12");
        assertTrue(lookup.linkedOwners(source).isEmpty());
        jdbc.update("UPDATE control_external_user_binding SET business_user_id=2 WHERE id=12");
        jdbc.update("UPDATE control_business_user SET status='DISABLED' WHERE id=2");
        assertTrue(lookup.linkedOwners(source).isEmpty());
    }

    @Test void tenantMismatchAndCaseInsensitiveStateCannotGrantAccess() {
        jdbc.update("UPDATE control_business_user SET tenant_id='TENANT' WHERE id=2");
        assertTrue(lookup.linkedOwners(source).isEmpty());
        jdbc.update("UPDATE control_business_user SET tenant_id='tenant' WHERE id=2");
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='active' WHERE id=5");
        assertTrue(lookup.linkedOwners(source).isEmpty());
    }

    @Test void reverifiedLinkPreservesOldSnapshotAndOnlyOneActivePair() {
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO control_personal_memory_identity_link VALUES (6,'tenant',11,12,1,2,'erp','Alice','crm','bob','ACTIVE',1)"));
        jdbc.update("UPDATE control_personal_memory_identity_link SET state='REVOKED', active_marker=NULL WHERE id=5");
        jdbc.update("INSERT INTO control_personal_memory_identity_link VALUES (6,'tenant',11,12,1,2,'erp','Alice','crm','bob','ACTIVE',1)");
        assertEquals(6, lookup.linkedOwners(source).get(0).linkId());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM control_personal_memory_identity_link", Integer.class));
    }

    @Test void caseVariantsOfSameSystemCannotBeUsedAsCrossSystemLink() {
        jdbc.update("UPDATE control_external_user_binding SET app_id='ERP' WHERE id=12");
        jdbc.update("UPDATE control_personal_memory_identity_link SET right_app_id='ERP' WHERE id=5");
        assertTrue(lookup.linkedOwners(source).isEmpty());
    }
}
