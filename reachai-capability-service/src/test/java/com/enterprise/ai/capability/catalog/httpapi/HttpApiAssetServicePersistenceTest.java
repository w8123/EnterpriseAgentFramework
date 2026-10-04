package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real H2/MyBatis coverage for the isolated HTTP API asset/source-binding lifecycle. */
class HttpApiAssetServicePersistenceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpApiServiceScope ordersProd = new HttpApiServiceScope(41L, "orders", "prod");

    private JdbcTemplate jdbc;
    private HttpApiAssetMapper assets;
    private HttpApiSourceBindingMapper bindings;
    private HttpApiAssetService service;
    private TransactionTemplate tx;

    @BeforeEach
    void database() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:http_api_assets_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createTable("capability_http_api_asset");
        createTable("capability_http_api_source_binding");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HttpApiAssetMapper.class);
        configuration.addMapper(HttpApiSourceBindingMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        assets = session.getMapper(HttpApiAssetMapper.class);
        bindings = session.getMapper(HttpApiSourceBindingMapper.class);
        service = new HttpApiAssetService(assets, bindings, new HttpApiContractCanonicalizer(json));
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void declaresReadCommittedTransactionsForCurrentBindingReads() throws Exception {
        Transactional observe = HttpApiAssetService.class
                .getMethod("observe", HttpApiAssetService.ObserveRequest.class)
                .getAnnotation(Transactional.class);
        Transactional remove = HttpApiAssetService.class
                .getMethod("remove", HttpApiAssetService.RemoveRequest.class)
                .getAnnotation(Transactional.class);

        assertNotNull(observe);
        assertNotNull(remove);
        assertEquals(Isolation.READ_COMMITTED, observe.isolation());
        assertEquals(Isolation.READ_COMMITTED, remove.isolation());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, tx.getIsolationLevel());
    }

    @Test
    void observesFirstEquivalentConflictAndReplayRevisionWithoutOverwritingOtherSources() throws Exception {
        HttpApiOperationContract equivalent = contract("READ_ONLY");
        HttpApiAssetService.Observation first = observe(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find", "OrdersController.java:42", "starter-r1", equivalent);

        assertTrue(first.assetCreated());
        assertEquals(HttpApiAssetStatus.DISCOVERED.name(), first.asset().getStatus());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(), first.binding().getStatus());
        String firstContractHash = first.binding().getSourceContractHash();
        Long assetId = first.asset().getId();

        HttpApiAssetService.Observation second = observe(ordersProd, HttpApiSourceKind.CONTROLLER_SCAN,
                "scan:orders-controller#find", "src/OrdersController.java:42", "scan-r1", equivalent);
        assertFalse(second.assetCreated());
        assertEquals(assetId, second.asset().getId());
        assertEquals(HttpApiAssetStatus.DISCOVERED.name(), second.asset().getStatus());
        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(), second.binding().getStatus());
        assertEquals(HttpApiSourceBindingStatus.EQUIVALENT.name(), binding(HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find").getStatus());

        HttpApiAssetService.Observation replay = observe(ordersProd, HttpApiSourceKind.CONTROLLER_SCAN,
                "scan:orders-controller#find", "src/v2/OrdersController.java:59", "scan-r2", equivalent);
        assertFalse(replay.assetCreated());
        assertEquals(assetId, replay.asset().getId(), "source location/revision do not form HTTP API identity");
        assertEquals(2L, bindings.selectCount(null));
        assertEquals("scan-r2", binding(HttpApiSourceKind.CONTROLLER_SCAN,
                "scan:orders-controller#find").getSourceRevision());
        assertEquals("src/v2/OrdersController.java:59", binding(HttpApiSourceKind.CONTROLLER_SCAN,
                "scan:orders-controller#find").getSourceLocation());
        assertEquals("starter-r1", binding(HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find").getSourceRevision());
        assertEquals(firstContractHash, binding(HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find").getSourceContractHash());

        HttpApiAssetService.Observation conflicting = observe(ordersProd, HttpApiSourceKind.OPENAPI_SCAN,
                "openapi:/gateway/orders/{id}", "openapi/orders.yaml#/paths/find", "openapi-r1", contract("WRITE"));
        assertEquals(assetId, conflicting.asset().getId());
        assertEquals(HttpApiAssetStatus.CONFLICT.name(), conflicting.asset().getStatus());
        assertEquals(HttpApiSourceBindingStatus.CONFLICT.name(), conflicting.binding().getStatus());
        assertEquals(firstContractHash, binding(HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find").getSourceContractHash(),
                "a conflicting source must not overwrite the independent source observation");
        assertEquals(3L, bindings.selectCount(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getAssetId, assetId)));
        assertTrue(bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getAssetId, assetId))
                .stream().allMatch(item -> HttpApiSourceBindingStatus.CONFLICT.name().equals(item.getStatus())));
    }

    @Test
    void identicalReplayRefreshesObservedAtWithoutCreatingOrRewritingTheBinding() throws Exception {
        HttpApiAssetService.Observation first = observe(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find", "OrdersController.java:42", "starter-r1", contract("READ_ONLY"));
        Long bindingId = first.binding().getId();
        String contractHash = first.binding().getSourceContractHash();
        String contractJson = first.binding().getSourceContractJson();
        var createdAt = first.binding().getCreatedAt();
        jdbc.update("UPDATE capability_http_api_source_binding SET observed_at = TIMESTAMP '2000-01-01 00:00:00' WHERE id = ?",
                bindingId);
        var beforeReplay = bindings.selectById(bindingId);

        HttpApiAssetService.Observation replay = observe(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find", "OrdersController.java:42", "starter-r1", contract("READ_ONLY"));
        var afterReplay = bindings.selectById(bindingId);

        assertFalse(replay.assetCreated());
        assertEquals(bindingId, replay.binding().getId());
        assertEquals(1L, bindings.selectCount(null));
        assertEquals(createdAt, afterReplay.getCreatedAt());
        assertEquals(contractHash, afterReplay.getSourceContractHash());
        assertEquals(contractJson, afterReplay.getSourceContractJson());
        assertTrue(afterReplay.getObservedAt().isAfter(beforeReplay.getObservedAt()),
                "every successful observation refreshes source freshness even when all facts are identical");
    }

    @Test
    void removalRetainsAssetAndRecomputesEquivalentConflictAndSourceMissingStates() throws Exception {
        HttpApiAssetService.Observation first = observe(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find", "OrdersController.java:42", "starter-r1", contract("READ_ONLY"));
        observe(ordersProd, HttpApiSourceKind.CONTROLLER_SCAN, "scan:orders-controller#find",
                "src/OrdersController.java:42", "scan-r1", contract("READ_ONLY"));
        observe(ordersProd, HttpApiSourceKind.OPENAPI_SCAN, "openapi:/gateway/orders/{id}",
                "openapi/orders.yaml#/paths/find", "openapi-r1", contract("WRITE"));
        assertEquals(HttpApiAssetStatus.CONFLICT.name(), assets.selectById(first.asset().getId()).getStatus());

        HttpApiAssetService.Removal removeConflict = remove(ordersProd, HttpApiSourceKind.OPENAPI_SCAN,
                "openapi:/gateway/orders/{id}");
        assertTrue(removeConflict.changed());
        assertEquals(HttpApiAssetStatus.DISCOVERED.name(), removeConflict.asset().getStatus());
        assertEquals(HttpApiSourceBindingStatus.REMOVED.name(), binding(HttpApiSourceKind.OPENAPI_SCAN,
                "openapi:/gateway/orders/{id}").getStatus());
        assertNotNull(binding(HttpApiSourceKind.OPENAPI_SCAN, "openapi:/gateway/orders/{id}").getRemovedAt());
        assertTrue(List.of("orders-controller#find", "scan:orders-controller#find").stream()
                .allMatch(key -> HttpApiSourceBindingStatus.EQUIVALENT.name().equals(
                        binding(key.startsWith("scan:") ? HttpApiSourceKind.CONTROLLER_SCAN : HttpApiSourceKind.STARTER_MVC,
                                key).getStatus())));

        HttpApiAssetService.Removal removeOneEquivalent = remove(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find");
        assertTrue(removeOneEquivalent.changed());
        assertEquals(HttpApiAssetStatus.DISCOVERED.name(), removeOneEquivalent.asset().getStatus());
        assertEquals(HttpApiSourceBindingStatus.DISCOVERED.name(), binding(HttpApiSourceKind.CONTROLLER_SCAN,
                "scan:orders-controller#find").getStatus());

        HttpApiAssetService.Removal removeLast = remove(ordersProd, HttpApiSourceKind.CONTROLLER_SCAN,
                "scan:orders-controller#find");
        assertTrue(removeLast.changed());
        assertEquals(HttpApiAssetStatus.SOURCE_MISSING.name(), removeLast.asset().getStatus());
        assertNotNull(assets.selectById(first.asset().getId()), "a removed source never deletes its asset");
        assertEquals(1L, assets.selectCount(null));
        assertNull(assets.selectById(first.asset().getId()).getAcceptedContractHash());
        assertNull(assets.selectById(first.asset().getId()).getToolDefinitionId());

        HttpApiAssetService.Removal repeated = remove(ordersProd, HttpApiSourceKind.CONTROLLER_SCAN,
                "scan:orders-controller#find");
        assertFalse(repeated.changed());
        assertEquals(HttpApiAssetStatus.SOURCE_MISSING.name(), repeated.asset().getStatus());
        assertEquals(3L, bindings.selectCount(null), "removal keeps every source fact for explanation");
    }

    @Test
    void movingTheSameSourceKeyToAnotherOperationRecomputesTheOldAsset() throws Exception {
        HttpApiAssetService.Observation first = observe(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find", "OrdersController.java:42", "starter-r1", contract("READ_ONLY"));
        Long oldAssetId = first.asset().getId();

        HttpApiAssetService.Observation moved = observe(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find", "OrdersController.java:88", "starter-r2",
                operation("GET", "/gateway", "/orders-v2/{id}", conditions("internal"), "READ_ONLY"));

        assertTrue(moved.assetCreated(), "the source moved to a newly discovered operation asset");
        assertNotNull(moved.asset());
        assertNotEquals(oldAssetId, moved.asset().getId());
        assertEquals(2L, assets.selectCount(null));
        assertEquals(HttpApiAssetStatus.SOURCE_MISSING.name(), assets.selectById(oldAssetId).getStatus());
        assertEquals(moved.asset().getId(), binding(HttpApiSourceKind.STARTER_MVC,
                "orders-controller#find").getAssetId());
        assertEquals(0L, bindings.selectCount(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getAssetId, oldAssetId)
                .ne(HttpApiSourceBindingEntity::getStatus, HttpApiSourceBindingStatus.REMOVED.name())));
    }

    @Test
    void concurrentFirstObservationsOfEquivalentSourcesConvergeThroughDatabaseLocks() throws Exception {
        HttpApiAssetService concurrent = new HttpApiAssetService(
                blockFirstTwoNullLookups(assets, "selectOne"), bindings, new HttpApiContractCanonicalizer(json));
        runConcurrently(concurrent,
                new HttpApiAssetService.ObserveRequest(ordersProd, HttpApiSourceKind.STARTER_MVC,
                        "starter:concurrent", "OrdersController.java:42", "r1", contract("READ_ONLY")),
                new HttpApiAssetService.ObserveRequest(ordersProd, HttpApiSourceKind.CONTROLLER_SCAN,
                        "scan:concurrent", "src/OrdersController.java:42", "r1", contract("READ_ONLY")));

        assertEquals(1L, assets.selectCount(null));
        assertEquals(2L, bindings.selectCount(null));
        HttpApiAssetEntity asset = assets.selectOne(null);
        assertEquals(HttpApiAssetStatus.DISCOVERED.name(), asset.getStatus());
        assertTrue(bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getAssetId, asset.getId()))
                .stream().allMatch(binding -> HttpApiSourceBindingStatus.EQUIVALENT.name().equals(binding.getStatus())));
    }

    @Test
    void concurrentSameSourceObservationsUseOneBindingWithAssetBeforeBindingLocks() throws Exception {
        observe(ordersProd, HttpApiSourceKind.STARTER_MVC, "starter:seed", "OrdersController.java:42", "r1",
                contract("READ_ONLY"));
        HttpApiAssetService concurrent = new HttpApiAssetService(assets,
                blockFirstTwoNullLookups(bindings, "selectOne"),
                new HttpApiContractCanonicalizer(json));
        runConcurrently(concurrent,
                new HttpApiAssetService.ObserveRequest(ordersProd, HttpApiSourceKind.OPENAPI_SCAN,
                        "openapi:same-source", "orders.yaml#/read", "r1", contract("READ_ONLY")),
                new HttpApiAssetService.ObserveRequest(ordersProd, HttpApiSourceKind.OPENAPI_SCAN,
                        "openapi:same-source", "orders.yaml#/read", "r1", contract("READ_ONLY")));

        assertEquals(1L, assets.selectCount(null));
        assertEquals(2L, bindings.selectCount(null));
        assertEquals(1L, bindings.selectCount(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.OPENAPI_SCAN.name())
                .eq(HttpApiSourceBindingEntity::getSourceKey, "openapi:same-source")));
        assertTrue(bindings.selectList(null).stream()
                .allMatch(binding -> HttpApiSourceBindingStatus.EQUIVALENT.name().equals(binding.getStatus())));
    }

    @Test
    void concurrentSameSourceDifferentOperationsRereadTheUniqueSourceBinding() throws Exception {
        HttpApiOperationContract left = operation("GET", "/gateway", "/orders-left/{id}", conditions("internal"),
                "READ_ONLY");
        HttpApiOperationContract right = operation("GET", "/gateway", "/orders-right/{id}", conditions("internal"),
                "READ_ONLY");
        HttpApiAssetService.Observation leftSeed = observe(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "starter:left-seed", "OrdersController.java:42", "r1", left);
        HttpApiAssetService.Observation rightSeed = observe(ordersProd, HttpApiSourceKind.STARTER_MVC,
                "starter:right-seed", "OrdersController.java:56", "r1", right);

        HttpApiAssetService concurrent = new HttpApiAssetService(assets,
                blockFirstTwoInserts(bindings),
                new HttpApiContractCanonicalizer(json));
        runConcurrently(concurrent,
                new HttpApiAssetService.ObserveRequest(ordersProd, HttpApiSourceKind.OPENAPI_SCAN,
                        "openapi:shared-source", "orders-left.yaml#/read", "r1", left),
                new HttpApiAssetService.ObserveRequest(ordersProd, HttpApiSourceKind.OPENAPI_SCAN,
                        "openapi:shared-source", "orders-right.yaml#/read", "r1", right));

        assertEquals(2L, assets.selectCount(null));
        assertEquals(3L, bindings.selectCount(null));
        HttpApiSourceBindingEntity shared = binding(HttpApiSourceKind.OPENAPI_SCAN, "openapi:shared-source");
        assertNotNull(shared);
        assertTrue(List.of(leftSeed.asset().getId(), rightSeed.asset().getId()).contains(shared.getAssetId()));
        assertEquals(1L, bindings.selectCount(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.OPENAPI_SCAN.name())
                .eq(HttpApiSourceBindingEntity::getSourceKey, "openapi:shared-source")));
    }

    @Test
    void concurrentDifferentContractsConvergeToConflictInsteadOfStaleDiscoveredState() throws Exception {
        HttpApiAssetService concurrent = new HttpApiAssetService(
                blockFirstTwoNullLookups(assets, "selectOne"), bindings, new HttpApiContractCanonicalizer(json));
        runConcurrently(concurrent,
                new HttpApiAssetService.ObserveRequest(ordersProd, HttpApiSourceKind.STARTER_MVC,
                        "starter:conflict", "OrdersController.java:42", "r1", contract("READ_ONLY")),
                new HttpApiAssetService.ObserveRequest(ordersProd, HttpApiSourceKind.OPENAPI_SCAN,
                        "openapi:conflict", "orders.yaml#/read", "r1", contract("WRITE")));

        assertEquals(1L, assets.selectCount(null));
        assertEquals(2L, bindings.selectCount(null));
        HttpApiAssetEntity asset = assets.selectOne(null);
        assertEquals(HttpApiAssetStatus.CONFLICT.name(), asset.getStatus());
        assertTrue(bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getAssetId, asset.getId()))
                .stream().allMatch(binding -> HttpApiSourceBindingStatus.CONFLICT.name().equals(binding.getStatus())));
    }

    @Test
    void isolatesScopeAndIdentityVariantsAndNeverPersistsSecretBearingSourceFacts() throws Exception {
        HttpApiOperationContract read = contract("READ_ONLY");
        observe(ordersProd, HttpApiSourceKind.STARTER_MVC, "starter:orders", "OrdersController.java:42", "r1", read);
        observe(new HttpApiServiceScope(41L, "orders", "stage"), HttpApiSourceKind.STARTER_MVC,
                "starter:orders", "OrdersController.java:42", "r1", read);
        observe(new HttpApiServiceScope(41L, "orders-other", "prod"), HttpApiSourceKind.STARTER_MVC,
                "starter:orders", "OrdersController.java:42", "r1", read);
        observe(ordersProd, HttpApiSourceKind.STARTER_MVC, "starter:post", "OrdersController.java:55", "r1",
                operation("POST", "/gateway", "/orders/{id}", conditions("internal"), "READ_ONLY"));
        observe(ordersProd, HttpApiSourceKind.STARTER_MVC, "starter:alternate-route", "OrdersController.java:56", "r1",
                operation("GET", "/gateway", "/orders/{other}", conditions("internal"), "READ_ONLY"));
        observe(ordersProd, HttpApiSourceKind.STARTER_MVC, "starter:alternate-condition", "OrdersController.java:57", "r1",
                operation("GET", "/gateway", "/orders/{id}", conditions("external"), "READ_ONLY"));

        assertEquals(6L, assets.selectCount(null));
        assertEquals(6L, assets.selectList(null).stream().map(HttpApiAssetEntity::getIdentityHash).distinct().count());
        assertEquals(6L, assets.selectList(null).stream().map(HttpApiAssetEntity::getQualifiedName).distinct().count());

        HttpApiAssetEntity first = assets.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, 41L)
                .eq(HttpApiAssetEntity::getProjectCode, "orders")
                .eq(HttpApiAssetEntity::getEnvironment, "prod")
                .eq(HttpApiAssetEntity::getHttpMethod, "GET")
                .last("LIMIT 1"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO capability_http_api_asset
                    (project_id, project_code, environment, identity_hash, qualified_name, http_method,
                     route_template, mapping_conditions_json, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, first.getProjectId(), first.getProjectCode(), first.getEnvironment(), first.getIdentityHash(),
                first.getQualifiedName(), first.getHttpMethod(), first.getRouteTemplate(),
                first.getMappingConditionsJson(), HttpApiAssetStatus.DISCOVERED.name()));

        HttpApiSourceBindingEntity source = binding(HttpApiSourceKind.STARTER_MVC, "starter:orders");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO capability_http_api_source_binding
                    (asset_id, project_id, project_code, environment, source_kind, source_key,
                     source_contract_hash, source_contract_json, status, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, source.getAssetId(), source.getProjectId(), source.getProjectCode(), source.getEnvironment(),
                source.getSourceKind(), source.getSourceKey(), source.getSourceContractHash(),
                source.getSourceContractJson(), HttpApiSourceBindingStatus.DISCOVERED.name()));

        long beforeRejectedObservation = bindings.selectCount(null);
        assertThrows(IllegalArgumentException.class, () -> observe(ordersProd, HttpApiSourceKind.API_MARKET_OPERATION,
                "market:orders", "baseUrl=https://private.example", "r1", read));
        assertThrows(IllegalArgumentException.class, () -> observe(ordersProd, HttpApiSourceKind.API_MARKET_OPERATION,
                "market:orders", "market.yaml", "r1", operation("GET", "/gateway", "/orders/{id}",
                        new HttpApiOperationContract.MappingConditions(List.of(), List.of(),
                                List.of(condition(HttpApiMappingConditionKind.HEADER, "Cookie",
                                        HttpApiMappingConditionOperator.EQUALS, "actual-cookie"))), "READ_ONLY")));
        assertEquals(beforeRejectedObservation, bindings.selectCount(null));

        String persisted = source.getSourceContractJson().toLowerCase();
        assertFalse(persisted.contains("baseurl"));
        assertFalse(persisted.contains("credentialref"));
        assertFalse(persisted.contains("actual-cookie"));
        assertFalse(persisted.contains("actual-header"));
        assertFalse(persisted.contains("token="));
        assertFalse(persisted.contains("password="));
    }

    private void runConcurrently(HttpApiAssetService target, HttpApiAssetService.ObserveRequest first,
                                 HttpApiAssetService.ObserveRequest second) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<HttpApiAssetService.Observation> one = workers.submit(() -> observeAfterStart(target, first, start));
            Future<HttpApiAssetService.Observation> two = workers.submit(() -> observeAfterStart(target, second, start));
            start.countDown();
            assertNotNull(one.get(10, TimeUnit.SECONDS));
            assertNotNull(two.get(10, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private HttpApiAssetService.Observation observeAfterStart(HttpApiAssetService target,
                                                               HttpApiAssetService.ObserveRequest request,
                                                               CountDownLatch start) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent observation start timed out");
        }
        return tx.execute(status -> target.observe(request));
    }

    private HttpApiAssetMapper blockFirstTwoNullLookups(HttpApiAssetMapper delegate, String methodName) {
        return coordinatedMapper(delegate, HttpApiAssetMapper.class, methodName);
    }

    private HttpApiSourceBindingMapper blockFirstTwoNullLookups(HttpApiSourceBindingMapper delegate,
                                                                 String methodName) {
        return coordinatedMapper(delegate, HttpApiSourceBindingMapper.class, methodName);
    }

    private HttpApiSourceBindingMapper blockFirstTwoInserts(HttpApiSourceBindingMapper delegate) {
        CyclicBarrier inserts = new CyclicBarrier(2);
        AtomicInteger observedInserts = new AtomicInteger();
        Object proxy = Proxy.newProxyInstance(HttpApiSourceBindingMapper.class.getClassLoader(),
                new Class<?>[] {HttpApiSourceBindingMapper.class}, (ignored, method, arguments) -> {
                    if ("insert".equals(method.getName()) && observedInserts.incrementAndGet() <= 2) {
                        inserts.await(10, TimeUnit.SECONDS);
                    }
                    return invokeMapper(delegate, method, arguments);
                });
        return HttpApiSourceBindingMapper.class.cast(proxy);
    }

    private <T> T coordinatedMapper(T delegate, Class<T> mapperType, String methodName) {
        CyclicBarrier misses = new CyclicBarrier(2);
        AtomicInteger observedMisses = new AtomicInteger();
        Object proxy = Proxy.newProxyInstance(mapperType.getClassLoader(), new Class<?>[] {mapperType},
                (ignored, method, arguments) -> {
                    Object result = invokeMapper(delegate, method, arguments);
                    if (methodName.equals(method.getName()) && result == null
                            && observedMisses.incrementAndGet() <= 2) {
                        misses.await(10, TimeUnit.SECONDS);
                    }
                    return result;
                });
        return mapperType.cast(proxy);
    }

    private Object invokeMapper(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private HttpApiAssetService.Observation observe(HttpApiServiceScope scope, HttpApiSourceKind kind, String key,
                                                     String location, String revision,
                                                     HttpApiOperationContract contract) {
        return tx.execute(status -> service.observe(new HttpApiAssetService.ObserveRequest(
                scope, kind, key, location, revision, contract)));
    }

    private HttpApiAssetService.Removal remove(HttpApiServiceScope scope, HttpApiSourceKind kind, String key) {
        return tx.execute(status -> service.remove(new HttpApiAssetService.RemoveRequest(scope, kind, key)));
    }

    private HttpApiSourceBindingEntity binding(HttpApiSourceKind kind, String key) {
        return bindings.selectOne(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getProjectId, ordersProd.projectId())
                .eq(HttpApiSourceBindingEntity::getProjectCode, ordersProd.projectCode())
                .eq(HttpApiSourceBindingEntity::getEnvironment, ordersProd.environment())
                .eq(HttpApiSourceBindingEntity::getSourceKind, kind.name())
                .eq(HttpApiSourceBindingEntity::getSourceKey, key)
                .last("LIMIT 1"));
    }

    private HttpApiOperationContract contract(String sideEffect) throws Exception {
        return operation("GET", "/gateway", "/orders/{id}", conditions("internal"), sideEffect);
    }

    private HttpApiOperationContract operation(String method, String contextPath, String endpointPath,
                                               HttpApiOperationContract.MappingConditions conditions,
                                               String sideEffect) throws Exception {
        JsonNode objectSchema = json.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}");
        JsonNode stringSchema = json.readTree("{\"type\":\"string\"}");
        return new HttpApiOperationContract(method, contextPath, endpointPath, conditions, List.of(
                new HttpApiOperationContract.Parameter("id", HttpApiParameterLocation.PATH, true,
                        json.readTree("{\"type\":\"string\"}"), List.of()),
                new HttpApiOperationContract.Parameter("include", HttpApiParameterLocation.QUERY, false,
                        stringSchema, List.of("application/json"))),
                new HttpApiOperationContract.RequestBody(true, objectSchema, List.of("application/json")),
                List.of(new HttpApiOperationContract.Response("200", objectSchema, List.of("application/json"))),
                new HttpApiOperationContract.AuthenticationRequirement(true, List.of("oauth2"),
                        List.of("X-Request-Id")), sideEffect);
    }

    private HttpApiOperationContract.MappingConditions conditions(String mode) {
        return new HttpApiOperationContract.MappingConditions(List.of("application/json"), List.of("application/json"),
                List.of(
                        condition(HttpApiMappingConditionKind.HEADER, "X-Mode",
                                HttpApiMappingConditionOperator.EQUALS, mode),
                        condition(HttpApiMappingConditionKind.PARAM, "version",
                                HttpApiMappingConditionOperator.EQUALS, "v1")));
    }

    private HttpApiOperationContract.MappingCondition condition(HttpApiMappingConditionKind kind, String name,
                                                                 HttpApiMappingConditionOperator operator, String value) {
        return new HttpApiOperationContract.MappingCondition(kind, name, operator, value);
    }

    private void createTable(String table) throws Exception {
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        var definition = Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?" + table + "`?\\s*\\(.*?;")
                .matcher(baseline);
        assertTrue(definition.find(), "baseline missing " + table);
        String sql = definition.group()
                .replaceAll("(?is)\\) ENGINE=.*?;", ");")
                .replaceAll("(?i)CHARACTER SET \\w+", "")
                .replaceAll("(?i)COLLATE \\w+", "")
                .replaceAll("(?is)ENUM\\([^)]*\\)", "VARCHAR(32)")
                .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`" + table + "_$2`")
                .replaceAll("(?i)\\bJSON\\b", "LONGTEXT");
        jdbc.execute(sql);
    }
}
