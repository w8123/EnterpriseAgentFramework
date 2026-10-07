package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.registry.*;
import com.enterprise.ai.agent.registry.RegistryContracts.*;
import com.enterprise.ai.capability.internal.CapabilityEmbedCredentialPolicyInternalService;
import com.enterprise.ai.capability.internal.CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

/** Real enrollment, signing and registry services share the actual Spring/MyBatis transaction. */
class RegistryEnrollmentPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    AnnotationConfigApplicationContext context;
    RegistryEnrollmentService enrollment;
    CapabilityRegistryService registry;
    ScanProjectMapper projects;
    RegistryCredentialMapper credentials;
    RegistryEnrollmentTokenMapper tokens;
    JdbcTemplate jdbc;
    CredentialWriteGate gate;
    RegistryMysqlTestDatabase mysql;

    @BeforeEach void database() throws Exception {
        javax.sql.DataSource datasource;
        if(Boolean.getBoolean("reachai.mysql.enrollmentVerification")) {
            mysql=RegistryMysqlTestDatabase.enrollment();
            datasource=mysql;
        } else {
            datasource=new DriverManagerDataSource("jdbc:h2:mem:enrollment_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        }
        jdbc=new JdbcTemplate(datasource);
        String baseline=Files.readString(Path.of("../sql/initV2.sql"));
        for(String table:List.of("capability_scan_project","capability_registry_project_credential","capability_registry_enrollment_token")){
            if(mysql==null) {
            var match=Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?"+table+"`?\\s*\\(.*?\\) ENGINE[^\\r\\n]*;").matcher(baseline);
            assertTrue(match.find());
            jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                    .replaceAll("(?i)CHARACTER SET \\w+", "").replaceAll("(?i)COLLATE \\w+", "")
                    .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`"+table+"_$2`")
                    .replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
            var additions=Pattern.compile("CALL add_col_if_absent\\('"+table+"',\\s*'([^']+)',\\s*'((?:''|[^'])*)'\\);").matcher(baseline);
            while(additions.find()) jdbc.execute("ALTER TABLE `"+table+"` ADD COLUMN IF NOT EXISTS `"+additions.group(1)+"` "+additions.group(2).replace("''","'").replaceAll("(?i)\\s+AFTER\\s+`[^`]+`", "").replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
            }
            var uniqueIndexes=Pattern.compile("CALL add_unique_idx_if_absent\\('"+table+"',\\s*'([^']+)',\\s*'([^']+)'\\);").matcher(baseline);
            while(uniqueIndexes.find()) {
                String name=uniqueIndexes.group(1);
                if(mysql==null || jdbc.queryForList("SHOW INDEX FROM `"+table+"` WHERE Key_name=?",name).isEmpty())
                    jdbc.execute("CREATE UNIQUE INDEX `"+(mysql==null ? table+"_" : "")+name+"` ON `"+table+"` ("+uniqueIndexes.group(2)+")");
            }
        }
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        gate=new CredentialWriteGate();config.addInterceptor(gate);
        for(Class<?> type:List.of(ScanProjectMapper.class,RegistryCredentialMapper.class,RegistryEnrollmentTokenMapper.class))config.addMapper(type);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(datasource);factory.setConfiguration(config);
        var session=new SqlSessionTemplate(factory.getObject());
        projects=session.getMapper(ScanProjectMapper.class);credentials=session.getMapper(RegistryCredentialMapper.class);tokens=session.getMapper(RegistryEnrollmentTokenMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(datasource));
        context.registerBean(RegistryEnrollmentService.class,()->new RegistryEnrollmentService(tokens,projects));
        context.registerBean(RegistrySecurityService.class,()->new RegistrySecurityService(credentials,new ObjectMapper()));
        context.registerBean(RegistryProjectRegistrationService.class,()->new RegistryProjectRegistrationService(projects,
                context.getBean(RegistrySecurityService.class),context.getBean(RegistryEnrollmentService.class)));
        context.registerBean(CapabilityRegistryService.class,()->new CapabilityRegistryService(projects,null,null,
                context.getBean(RegistrySecurityService.class),context.getBean(RegistryProjectRegistrationService.class),new ObjectMapper(),null,null,null,null,null,null));
        context.refresh();enrollment=context.getBean(RegistryEnrollmentService.class);registry=context.getBean(CapabilityRegistryService.class);
        assertTrue(AopUtils.isAopProxy(registry));assertTrue(AopUtils.isAopProxy(enrollment));
    }
    @AfterEach void close(){
        try { if(context!=null)context.close(); }
        finally { if(mysql!=null)mysql.close(); }
    }

    @Test void registrationComponentRequiresItsCallerTransaction() {
        var registration=context.getBean(RegistryProjectRegistrationService.class);
        assertTrue(AopUtils.isAopProxy(registration));
        var issued=enrollment.create("orders",7L);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                ()->registration.registerProject(request("orders",600),issued.enrollmentToken(),null));
        assertEquals(0,projects.selectCount(null));assertEquals(0,credentials.selectCount(null));
        assertNull(tokens.selectOne(null).getConsumedAt());
    }

    @Test void policyUpdateCannotReviveConcurrentlyRevokedCredential() throws Exception {
        var response=register();
        race("UPDATE capability_registry_project_credential SET status='DISABLED'",
                ()->{
                    var failure=assertThrows(IllegalArgumentException.class,
                            ()->context.getBean(RegistrySecurityService.class).updateEmbedPolicy("orders",response.appKey(),null,null,900));
                    assertEquals("registry credential policy changed concurrently",failure.getMessage());
                });
        assertEquals("DISABLED",credentials.selectOne(null).getStatus());
        assertEquals(600,credentials.selectOne(null).getTokenTtlSeconds());
    }

    @Test void policyUpdateCannotRestoreRotatedCredentialIdentity() throws Exception {
        var response=register();
        race("UPDATE capability_registry_project_credential SET app_key='rotated-test-key',app_secret='rotated-test-secret'",
                ()->{
                    var failure=assertThrows(IllegalArgumentException.class,
                            ()->context.getBean(RegistrySecurityService.class).updateEmbedPolicy("orders",response.appKey(),null,null,900));
                    assertEquals("registry credential policy changed concurrently",failure.getMessage());
                });
        assertEquals("rotated-test-key",credentials.selectOne(null).getAppKey());
        assertEquals("rotated-test-secret",credentials.selectOne(null).getAppSecret());
        assertEquals(600,credentials.selectOne(null).getTokenTtlSeconds());
    }

    @Test void ttlOnlyPolicyUpdatePreservesConcurrentlyChangedOriginsAndSecret() throws Exception {
        var response=register();
        race("UPDATE capability_registry_project_credential SET allowed_origins_json='[\"https://new.invalid\"]',app_secret='rotated-test-secret'",
                ()->context.getBean(RegistrySecurityService.class).updateEmbedPolicy("orders",response.appKey(),null,null,900));
        assertEquals("[\"https://new.invalid\"]",credentials.selectOne(null).getAllowedOriginsJson());
        assertEquals("rotated-test-secret",credentials.selectOne(null).getAppSecret());
        assertEquals(900,credentials.selectOne(null).getTokenTtlSeconds());
    }

    @Test void administrativePolicyCannotRestoreConcurrentlyRotatedCredentialIdentity() throws Exception {
        register();
        Long id=credentials.selectOne(null).getId();
        var returned=new java.util.concurrent.atomic.AtomicReference<CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView>();
        race("UPDATE capability_registry_project_credential SET app_key='rotated-test-key',app_secret='rotated-test-secret'",
                ()->returned.set(administrativePolicy().updatePolicy(id,new EmbedCredentialPolicyUpdate(
                        List.of("https://changed.invalid"),List.of("changed-agent"),900,null))));
        var saved=credentials.selectOne(null);
        assertEquals("rotated-test-key",saved.getAppKey(),"policy updates cannot restore a replaced app key");
        assertTrue("rotated-test-secret".equals(saved.getAppSecret()),"policy updates cannot restore a replaced secret");
        assertEquals("rotated-test-key",returned.get().appKey(),"the response must describe the saved credential");
        assertEquals(900,saved.getTokenTtlSeconds());
    }

    @Test void administrativePolicyWithoutStatusCannotUndoConcurrentDisable() throws Exception {
        register();
        Long id=credentials.selectOne(null).getId();
        var returned=new java.util.concurrent.atomic.AtomicReference<CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView>();
        race("UPDATE capability_registry_project_credential SET status='DISABLED'",
                ()->returned.set(administrativePolicy().updatePolicy(id,new EmbedCredentialPolicyUpdate(
                        List.of("https://changed.invalid"),List.of(),900,null))));
        assertEquals("DISABLED",credentials.selectOne(null).getStatus(),"an omitted status must not reactivate the credential");
        assertEquals("DISABLED",returned.get().status());
        assertEquals(900,credentials.selectOne(null).getTokenTtlSeconds());
    }

    @Test void credentialRotationDoesNotOverwriteConcurrentPolicyEdit() throws Exception {
        var registered=register();
        var returned=new java.util.concurrent.atomic.AtomicReference<RegistryCredentialEntity>();
        race("UPDATE capability_registry_project_credential SET allowed_origins_json='[\"https://changed.invalid\"]',allowed_agent_ids_json='[\"changed-agent\"]',token_ttl_seconds=1200",
                ()->returned.set(context.getBean(RegistrySecurityService.class).savePrimaryCredential(
                        registered.projectId(),"orders","new-test-key","new-test-secret")));
        var saved=credentials.selectOne(null);
        assertEquals(1200,saved.getTokenTtlSeconds(),"rotation does not own the policy fields");
        assertEquals("[\"https://changed.invalid\"]",saved.getAllowedOriginsJson());
        assertEquals("[\"changed-agent\"]",saved.getAllowedAgentIdsJson());
        assertEquals(1200,returned.get().getTokenTtlSeconds(),"rotation returns the merged saved state");
        assertEquals("new-test-key",saved.getAppKey());
    }

    @Test void credentialRotationCannotReactivateConcurrentlyDisabledCredential() throws Exception {
        var registered=register();
        var rejected=assertThrows(IllegalArgumentException.class,()->race(
                "UPDATE capability_registry_project_credential SET status='DISABLED'",
                ()->context.getBean(RegistrySecurityService.class).savePrimaryCredential(
                        registered.projectId(),"orders","new-test-key","new-test-secret")));
        assertEquals("registry credential changed during rotation; reload and retry",rejected.getMessage());
        assertEquals("DISABLED",credentials.selectOne(null).getStatus());
        assertTrue(registered.appKey().equals(credentials.selectOne(null).getAppKey()));
    }

    @Test void credentialRotationCannotOverwriteAnotherCommittedRotation() throws Exception {
        var registered=register();
        var rejected=assertThrows(IllegalArgumentException.class,()->race(
                "UPDATE capability_registry_project_credential SET app_key='other-test-key',app_secret='other-test-secret'",
                ()->context.getBean(RegistrySecurityService.class).savePrimaryCredential(
                        registered.projectId(),"orders","new-test-key","new-test-secret")));
        assertEquals("registry credential changed during rotation; reload and retry",rejected.getMessage());
        assertEquals("other-test-key",credentials.selectOne(null).getAppKey());
        assertTrue("other-test-secret".equals(credentials.selectOne(null).getAppSecret()));
    }

    @Test void credentialRotationDetectsIdentityChangesThatDifferOnlyInCase() throws Exception {
        var registered=register();
        jdbc.update("UPDATE capability_registry_project_credential SET app_key='case-test-key',app_secret='CaseSensitiveTestSecret'");
        assertThrows(IllegalArgumentException.class,()->race(
                "UPDATE capability_registry_project_credential SET app_key='CASE-TEST-KEY',app_secret='casesensitivetestsecret'",
                ()->context.getBean(RegistrySecurityService.class).savePrimaryCredential(
                        registered.projectId(),"orders","new-test-key","new-test-secret")));
        assertEquals("CASE-TEST-KEY",credentials.selectOne(null).getAppKey());
        assertTrue("casesensitivetestsecret".equals(credentials.selectOne(null).getAppSecret()));
    }

    @Test void credentialRotationDetectsIdentityLengthChangesHiddenByBinaryPadding() throws Exception {
        var registered=register();
        jdbc.update("UPDATE capability_registry_project_credential SET app_key='case-test-key',app_secret='same-test-secret'");
        assertThrows(IllegalArgumentException.class,()->race(
                "UPDATE capability_registry_project_credential SET app_secret=CONCAT(app_secret,CHAR(0))",
                ()->context.getBean(RegistrySecurityService.class).savePrimaryCredential(
                        registered.projectId(),"orders","new-test-key","new-test-secret")));
        assertEquals("case-test-key",credentials.selectOne(null).getAppKey());
        assertTrue("same-test-secret\u0000".equals(credentials.selectOne(null).getAppSecret()));
    }

    @Test void primarySelectionAndRotationUseTheSameDeterministicCredentialOrder() {
        var registered=register();
        var newer=new RegistryCredentialEntity();
        newer.setProjectId(registered.projectId());newer.setProjectCode("orders");
        newer.setAppKey("second-test-key");newer.setAppSecret("second-test-secret");newer.setStatus("ACTIVE");
        credentials.insert(newer);
        jdbc.update("UPDATE capability_registry_project_credential SET updated_at=?",java.time.LocalDateTime.of(2026,9,10,10,0));
        Long expected=administrativePolicy().listPolicies("orders","ACTIVE",10).get(0).id();
        var security=context.getBean(RegistrySecurityService.class);
        assertEquals(newer.getId(),expected);
        assertEquals(expected,security.findPrimaryActiveCredential("orders").orElseThrow().getId(),
                "primary selection must match the policy list when timestamps tie");
        assertEquals(expected,security.savePrimaryCredential(registered.projectId(),"orders","new-test-key","new-test-secret").getId());
        assertEquals(2,credentials.selectCount(null),"multi-credential projects remain supported");
    }

    @Test void administrativePolicyCanClearListsAndExplicitlyDisable() {
        register();
        Long id=credentials.selectOne(null).getId();
        var saved=administrativePolicy().updatePolicy(id,new EmbedCredentialPolicyUpdate(List.of(),List.of(),100000," DISABLED "));
        assertEquals("[]",saved.allowedOriginsJson());assertEquals("[]",saved.allowedAgentIdsJson());
        assertEquals(86400,saved.tokenTtlSeconds());assertEquals("DISABLED",saved.status());
    }

    @Test void administrativePolicyDefaultRequestPreservesIdentityAndStatus() {
        var registered=register();
        jdbc.update("UPDATE capability_registry_project_credential SET status='DISABLED'");
        var saved=administrativePolicy().updatePolicy(credentials.selectOne(null).getId(),null);
        assertTrue(registered.appKey().equals(saved.appKey()));assertEquals("DISABLED",saved.status());
        assertEquals("[]",saved.allowedOriginsJson());assertEquals("[]",saved.allowedAgentIdsJson());
        assertEquals(600,saved.tokenTtlSeconds());
    }

    private CapabilityEmbedCredentialPolicyInternalService administrativePolicy() {
        return new CapabilityEmbedCredentialPolicyInternalService(credentials,new ObjectMapper(),context.getBean(RegistrySecurityService.class));
    }

    private RegistryProjectResponse register(){
        return registry.registerProject(request("orders",600),enrollment.create("orders",7L).enrollmentToken(),null);
    }

    private void race(String concurrentSql,Runnable policy) throws Exception {
        var writer=java.util.concurrent.Executors.newSingleThreadExecutor();
        gate.beforeWrite=()->{
            try { writer.submit(()->jdbc.update(concurrentSql)).get(5,java.util.concurrent.TimeUnit.SECONDS); }
            catch(Exception failure){throw new IllegalStateException(failure);}
        };
        try {
            var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            tx.executeWithoutResult(status->policy.run());
            assertNull(gate.beforeWrite,"Policy write did not reach the interleaving gate");
        } finally {writer.shutdownNow();assertTrue(writer.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }

    @org.apache.ibatis.plugin.Intercepts({
            @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}),
            @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class})})
    static class CredentialWriteGate implements org.apache.ibatis.plugin.Interceptor {
        volatile Runnable beforeWrite;
        volatile java.util.concurrent.CyclicBarrier firstProjectReads;
        final java.util.concurrent.atomic.AtomicInteger synchronizedProjectReads=new java.util.concurrent.atomic.AtomicInteger();
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation)throws Throwable{
            var statement=(org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0];
            Runnable action=beforeWrite;
            if("update".equals(invocation.getMethod().getName()) && action!=null && statement.getId().startsWith(RegistryCredentialMapper.class.getName()+".")){
                beforeWrite=null;action.run();
            }
            Object result=invocation.proceed();
            var barrier=firstProjectReads;
            if(barrier!=null && "query".equals(invocation.getMethod().getName())
                    && statement.getId().equals(ScanProjectMapper.class.getName()+".selectList")
                    && result instanceof List<?> rows && rows.isEmpty()){
                synchronizedProjectReads.incrementAndGet();
                barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
            }
            return result;
        }
    }

    @Test void concurrentFirstRegistrationWithOneTokenIssuesOnlyOneCredential() throws Exception {
        assertOneProjectAfterConcurrentRegistration(true);
    }

    @Test void differentEnrollmentTokensCannotCreateTwoProjectsWithTheSameNormalizedCode() throws Exception {
        assertOneProjectAfterConcurrentRegistration(false);
    }

    private void assertOneProjectAfterConcurrentRegistration(boolean sharedToken) throws Exception {
        String first=enrollment.create("orders",7L).enrollmentToken();
        String second=sharedToken ? first : enrollment.create(" ORDERS ",7L).enrollmentToken();
        gate.firstProjectReads=new java.util.concurrent.CyclicBarrier(2);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        List<Object> results;
        try {
            var left=executor.submit(()->registrationOutcome("orders","First registration",first));
            var right=executor.submit(()->registrationOutcome(" ORDERS ","Second registration",second));
            results=List.of(left.get(15,java.util.concurrent.TimeUnit.SECONDS),right.get(15,java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            gate.firstProjectReads=null;
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(2,gate.synchronizedProjectReads.get(),"both registration transactions must observe the missing project");
        assertEquals(1,projects.selectCount(null),"one stable project code must identify one project even when enrollment tokens and names differ");
        assertEquals(1,credentials.selectCount(null));
        var winners=results.stream().filter(RegistryProjectResponse.class::isInstance).map(RegistryProjectResponse.class::cast).toList();
        assertEquals(1,winners.size());
        assertEquals(1,results.stream().filter(RuntimeException.class::isInstance).count());
        var rejected=results.stream().filter(RuntimeException.class::isInstance).map(RuntimeException.class::cast).findFirst().orElseThrow();
        assertInstanceOf(IllegalArgumentException.class,rejected);
        assertEquals(sharedToken ? "registry enrollment token has already been used"
                : "registry project registration conflicts with an existing project",rejected.getMessage());
        var winner=winners.get(0);
        assertEquals(winner.projectId(),projects.selectOne(null).getId());
        assertEquals(winner.projectId(),credentials.selectOne(null).getProjectId());
        assertEquals(winner.appKey(),credentials.selectOne(null).getAppKey());
        assertEquals(winner.appSecret(),credentials.selectOne(null).getAppSecret());
        assertEquals(1,tokens.selectList(null).stream().filter(token->token.getConsumedAt()!=null).count());
        if(!sharedToken) assertEquals(1,tokens.selectList(null).stream().filter(token->token.getConsumedAt()==null).count());
        assertNull(registry.registerProject(request("orders",600),null,signature(winner)).appSecret());
    }

    private Object registrationOutcome(String code,String name,String token) {
        var request=new ProjectRegisterRequest(code,name,"dev",null,"PRIVATE","http://orders.local","",null,null,
                List.of("https://test.invalid"),List.of("agent-test"),600,Map.of());
        try { return registry.registerProject(request,token,null); }
        catch(RuntimeException rejected) { return rejected; }
    }

    @Test void firstRegistrationCommitsCredentialAndConsumedTokenAndSignedRetryDoesNotReissue() throws Exception {
        var issued=enrollment.create("orders",7L);
        assertNotEquals(issued.enrollmentToken(),tokens.selectOne(null).getTokenDigest());
        var response=registry.registerProject(request("orders",600),issued.enrollmentToken(),null);
        assertNotNull(tokens.selectOne(null).getConsumedAt());
        assertEquals(1,projects.selectCount(null));assertEquals(1,credentials.selectCount(null));
        assertEquals(response.appKey(),credentials.selectOne(null).getAppKey());
        assertEquals(response.appSecret(),credentials.selectOne(null).getAppSecret());
        var again=registry.registerProject(request("orders",900),null,signature(response));
        assertNull(again.appKey());assertNull(again.appSecret());
        assertEquals(1,credentials.selectCount(null));assertEquals(900,credentials.selectOne(null).getTokenTtlSeconds());
    }

    @Test void lostEnrollmentResponseCanRecoverThroughCredentialRotationWithoutReusingToken() throws Exception {
        var token=enrollment.create("orders",7L);
        var lost=registry.registerProject(request("orders",600),token.enrollmentToken(),null);
        Long credentialId=credentials.selectOne(null).getId();
        var consumedAt=tokens.selectOne(null).getConsumedAt();
        assertThrows(IllegalArgumentException.class,
                ()->registry.registerProject(request("orders",600),token.enrollmentToken(),null));
        assertThrows(IllegalArgumentException.class,()->enrollment.create("orders",7L));

        var replacement=context.getBean(RegistrySecurityService.class).savePrimaryCredential(
                lost.projectId(),"orders","rak_operator_recovery_test","ras_operator_recovery_test");
        var recovered=new RegistryProjectResponse(lost.projectId(),"orders",lost.name(),lost.environment(),
                lost.visibility(),replacement.getAppKey(),replacement.getAppSecret());
        assertThrows(IllegalArgumentException.class,()->registry.registerProject(request("orders",600),null,signature(lost)));
        var registered=registry.registerProject(request("orders",600),null,signature(recovered));

        assertEquals(lost.projectId(),registered.projectId());
        assertNull(registered.appKey());assertNull(registered.appSecret());
        assertEquals(1,projects.selectCount(null));assertEquals(1,credentials.selectCount(null));
        assertEquals(credentialId,credentials.selectOne(null).getId());
        assertEquals(consumedAt,tokens.selectOne(null).getConsumedAt());
        assertEquals(600,credentials.selectOne(null).getTokenTtlSeconds());
    }

    @Test void policyPersistenceFailureRollsBackTokenProjectAndCredentialThenSameTokenCanRetry() {
        Assumptions.assumeTrue(mysql==null,"Constraint failure injection uses the H2 fixture");
        var issued=enrollment.create("orders",7L);
        jdbc.execute("ALTER TABLE capability_registry_project_credential ADD CONSTRAINT reject_policy CHECK (token_ttl_seconds < 1000)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                ()->registry.registerProject(request("orders",1200),issued.enrollmentToken(),null));
        assertEquals(0,projects.selectCount(null));assertEquals(0,credentials.selectCount(null));
        assertNull(tokens.selectOne(null).getConsumedAt());
        jdbc.execute("ALTER TABLE capability_registry_project_credential DROP CONSTRAINT reject_policy");
        registry.registerProject(request("orders",1200),issued.enrollmentToken(),null);
        assertEquals(1,projects.selectCount(null));assertEquals(1,credentials.selectCount(null));
        assertNotNull(tokens.selectOne(null).getConsumedAt());
    }

    @Test void wrongProjectAndExpiredTokenCannotCreateProjectOrConsumeEnrollment() {
        var issued=enrollment.create("orders",7L);
        assertThrows(IllegalArgumentException.class,()->registry.registerProject(request("other",600),issued.enrollmentToken(),null));
        jdbc.update("UPDATE capability_registry_enrollment_token SET expires_at=?",java.time.LocalDateTime.now().minusMinutes(1));
        assertThrows(IllegalArgumentException.class,()->registry.registerProject(request("orders",600),issued.enrollmentToken(),null));
        assertEquals(0,projects.selectCount(null));assertEquals(0,credentials.selectCount(null));
        assertNull(tokens.selectOne(null).getConsumedAt());
    }

    @Test void existingProjectCannotBeRegisteredAgainWithEnrollmentInsteadOfSignature() {
        var issued=enrollment.create("orders",7L);
        registry.registerProject(request("orders",600),issued.enrollmentToken(),null);
        assertThrows(IllegalArgumentException.class,()->registry.registerProject(request("orders",900),issued.enrollmentToken(),null));
        assertEquals(1,projects.selectCount(null));assertEquals(1,credentials.selectCount(null));
        assertEquals(600,credentials.selectOne(null).getTokenTtlSeconds());
    }

    private ProjectRegisterRequest request(String project,int ttl){
        return new ProjectRegisterRequest(project,"Test registration","dev",null,"PRIVATE","http://orders.local","",null,null,List.of("https://test.invalid"),List.of("agent-test"),ttl,Map.of());
    }
    private RegistrySecurityService.RegistrySignatureHeaders signature(RegistryProjectResponse response)throws Exception{
        String timestamp=Long.toString(System.currentTimeMillis()),nonce=UUID.randomUUID().toString();
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(response.appSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal((response.projectCode()+"\n"+timestamp+"\n"+nonce).getBytes(StandardCharsets.UTF_8)));
        return new RegistrySecurityService.RegistrySignatureHeaders(response.appKey(),timestamp,nonce,signature);
    }
}
