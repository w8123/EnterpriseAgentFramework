package com.enterprise.ai.internalauth;

import com.enterprise.ai.common.internalauth.*;
import com.enterprise.ai.controller.DocumentImportJobController;
import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.security.impl.PermissionServiceImpl;
import com.enterprise.ai.service.DocumentImportJobService;
import com.enterprise.ai.service.impl.*;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real baseline tables, mappers, metadata publication and HMAC-guarded MVC read/grant routes. */
class KnowledgeImporterReadAccessPersistenceTest {
    static final String SECRET = "importer-read-access-test-secret-over-32-bytes";
    static final String ROOT = KnowledgeBizIndexConsoleAuthFilter.DOCUMENT_IMPORT_PREFIX;
    static final String PATH = ROOT + "/files/file/read-access";
    static final String ASSERTION = "{\"workspaceId\":\"default\",\"projectCode\":\"orders\",\"resourceScope\":\"PROJECT\"}";
    KnowledgeQueryTestDatabase db;
    KnowledgeFileReadAccessService access;
    PermissionServiceImpl permissions;
    TransactionTemplate tx;
    MetadataPersistStep metadata;
    PipelineContext context;
    MockMvc mvc;
    KnowledgeBizIndexConsoleAuthFilter authFilter;

    @BeforeEach void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk",
                "knowledge_document_import_job", "knowledge_document_index_execution", "knowledge_user_file_permission"),
                KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class,
                DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class, UserFilePermissionRepository.class);
        var manager = new DataSourceTransactionManager(db.jdbc().getDataSource()); tx = new TransactionTemplate(manager);
        var jobs = db.mapper(DocumentImportJobRepository.class);
        access = new KnowledgeFileReadAccessService(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                jobs, db.mapper(UserFilePermissionRepository.class), manager);
        permissions = new PermissionServiceImpl(db.mapper(UserFilePermissionRepository.class), manager);
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,status,vector_collection_name,workspace_id,project_code,scope) VALUES (7,'公开实验','kb',1,'physical','default','orders','PROJECT')");
        db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,vector_collection_name,tenant_id,created_by_actor_id,workspace_id,project_code,resource_scope,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until) VALUES ('job','file',7,'kb','physical','default','42','default','orders','PROJECT','公开实验.txt','txt','source','JAVA_FAST','INDEXING','INDEXING','lease','2099-01-01 00:00:00')");
        var store = new DocumentIndexExecutionStore(jobs, db.mapper(DocumentIndexExecutionRepository.class),
                db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class), manager);
        metadata = new MetadataPersistStep(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), new ObjectMapper(),
                new DocumentImportPublicationGuard(jobs, store, mock(KnowledgeFileDeletionService.class), access),
                mock(KnowledgeTagService.class), mock(KnowledgeQuestionService.class));
        context = new PipelineContext(); context.setFileId("file"); context.setFileName("公开实验.txt");
        context.setKnowledgeBaseId(7L); context.setKnowledgeBaseCode("kb"); context.setVectorCollectionName("physical");
        context.setImportJobId("job"); context.setImportLeaseOwner("lease"); context.setChunks(List.of("青竹晨会实验"));
        var manifest = DocumentIndexVectorManifest.forExecution("file", "lease", 1);
        context.setVectorIds(manifest.batch(0, 1)); assertTrue(store.register(context, manifest)); store.acknowledge("lease");
        var nonceStore = mock(KnowledgeInternalNonceStore.class);
        Set<String> nonces = new HashSet<>(); when(nonceStore.tryConsume(any(), any(), any())).thenAnswer(call -> nonces.add(call.getArgument(1)));
        var filter = new KnowledgeBizIndexConsoleAuthFilter(SECRET, "", 300, 1048576, nonceStore); authFilter = filter;
        filter.configureMultipart(new jakarta.servlet.MultipartConfigElement("", 1048576, 1048576, 65536));
        mvc = MockMvcBuilders.standaloneSetup(new DocumentImportJobController(mock(DocumentImportJobService.class), new ObjectMapper(), access))
                .addFilters(filter).build();
    }
    @AfterEach void close() { if (db != null) db.close(); }

    @Test void publicationAndGrantCommitTogetherAndRealSnapshotResolvesOnlyImporterChunks() {
        tx.executeWithoutResult(status -> { metadata.process(context); status.setRollbackOnly(); });
        assertEquals(0, countGrants()); assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals("INDEXING", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
        tx.executeWithoutResult(status -> metadata.process(context));
        assertTrue(context.isImportPublished()); assertEquals(1, countGrants());
        var snapshot = permissions.capture("42"); assertEquals(List.of("file"), snapshot.fileIds());
        Long chunkId = db.jdbc().queryForObject("SELECT id FROM knowledge_chunk", Long.class);
        var authorized = permissions.resolveAuthorizedChunks(snapshot, List.of(chunkId));
        assertEquals(1, authorized.size()); assertEquals("青竹晨会实验", authorized.get(0).content());
        assertTrue(permissions.capture("99").grants().isEmpty());
    }

    @Test void signedUploadPersistsAttestedImporterAndPublicationMakesItsChunkReadable() throws Exception {
        db.jdbc().update("DELETE FROM knowledge_document_index_execution");
        db.jdbc().update("DELETE FROM knowledge_document_import_job");
        var properties = new DocumentImportJobProperties(); properties.setEnabled(false);
        var parseProperties = new com.enterprise.ai.pipeline.document.DocumentParseProperties();
        var router = new com.enterprise.ai.pipeline.document.DocumentParseRouter(
                new com.enterprise.ai.pipeline.document.DocumentFormatDetector(parseProperties), List.of());
        var service = new DocumentImportJobServiceImpl(db.mapper(DocumentImportJobRepository.class), db.mapper(KnowledgeBaseRepository.class),
                db.mapper(FileInfoRepository.class), mock(com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore.class),
                router, new ObjectMapper(), properties, mock(DocumentImportJobWorker.class),
                mock(com.enterprise.ai.pipeline.step.TextCleanStep.class), mock(com.enterprise.ai.pipeline.step.ChunkStep.class),
                Runnable::run, new DataSourceTransactionManager(db.jdbc().getDataSource()));
        var uploadMvc = MockMvcBuilders.standaloneSetup(new DocumentImportJobController(service, new ObjectMapper(), access))
                .addFilters(authFilter).build();
        String boundary = "read-access-fixture";
        StringBuilder body = new StringBuilder();
        for (var field : Map.of("knowledgeBaseCode", "kb", "workspaceId", "default", "projectCode", "orders", "resourceScope", "PROJECT", "userId", "client-victim").entrySet()) {
            body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(field.getKey())
                    .append("\"\r\n\r\n").append(field.getValue()).append("\r\n");
        }
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"public.txt\"\r\nContent-Type: text/plain\r\n\r\n青竹晨会实验\r\n--")
                .append(boundary).append("--\r\n");
        uploadMvc.perform(signed("POST", ROOT + "/jobs", body.toString(), "default", "42")
                .contentType("multipart/form-data; boundary=" + boundary).accept("application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));
        var job = db.mapper(DocumentImportJobRepository.class).selectList(null).get(0);
        assertEquals("42", job.getCreatedByActorId()); assertEquals("default", job.getTenantId()); assertEquals("orders", job.getProjectCode());
        assertEquals(0, countGrants()); // Submission and preview alone do not expose unpublished content.
        db.jdbc().update("UPDATE knowledge_document_import_job SET status='INDEXING',stage='INDEXING',lease_owner='uploaded-lease',lease_until='2099-01-01 00:00:00' WHERE job_id=?", job.getJobId());
        var c = new PipelineContext(); c.setFileId(job.getFileId()); c.setFileName(job.getFileName());
        c.setKnowledgeBaseId(7L); c.setKnowledgeBaseCode("kb"); c.setVectorCollectionName("physical");
        c.setImportJobId(job.getJobId()); c.setImportLeaseOwner("uploaded-lease"); c.setChunks(List.of("青竹晨会实验"));
        var manifest = DocumentIndexVectorManifest.forExecution(c.getFileId(), "uploaded-lease", 1); c.setVectorIds(manifest.batch(0, 1));
        var store = new DocumentIndexExecutionStore(db.mapper(DocumentImportJobRepository.class), db.mapper(DocumentIndexExecutionRepository.class),
                db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class),
                new DataSourceTransactionManager(db.jdbc().getDataSource()));
        assertTrue(store.register(c, manifest)); store.acknowledge("uploaded-lease");
        tx.executeWithoutResult(status -> metadata.process(c));
        var snapshot = permissions.capture("42"); assertEquals(List.of(job.getFileId()), snapshot.fileIds());
        assertTrue(permissions.capture("client-victim").grants().isEmpty());
        Long chunkId = db.jdbc().queryForObject("SELECT id FROM knowledge_chunk", Long.class);
        assertEquals("青竹晨会实验", permissions.resolveAuthorizedChunks(snapshot, List.of(chunkId)).get(0).content());
    }

    @Test void signedOwnGrantAndReadAreIdempotentAndKeepUnrelatedGrants() throws Exception {
        publish();
        db.jdbc().update("DELETE FROM knowledge_user_file_permission WHERE user_id='42' AND file_id='file'");
        mvc.perform(signed("GET", PATH, "", "default", "42")).andExpect(status().isOk()).andExpect(jsonPath("$.data.readable").value(false));
        db.jdbc().update("INSERT INTO knowledge_user_file_permission(user_id,file_id,permission_type,record_generation) VALUES ('other','other-file','write',?)", "e".repeat(32));
        mvc.perform(signed("POST", PATH, ASSERTION, "default", "42")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readable").value(true)).andExpect(jsonPath("$.data.userId").value("42"));
        String generation = db.jdbc().queryForObject("SELECT record_generation FROM knowledge_user_file_permission WHERE user_id='42'", String.class);
        mvc.perform(signed("POST", PATH, ASSERTION, "default", "42")).andExpect(status().isOk());
        mvc.perform(signed("GET", PATH, "", "default", "42")).andExpect(status().isOk()).andExpect(jsonPath("$.data.permissionType").value("read"));
        assertEquals(generation, db.jdbc().queryForObject("SELECT record_generation FROM knowledge_user_file_permission WHERE user_id='42'", String.class));
        assertEquals("write", db.jdbc().queryForObject("SELECT permission_type FROM knowledge_user_file_permission WHERE user_id='other'", String.class));
        assertEquals(2, countGrants()); assertEquals(1, permissions.capture("42").grants().size());
    }

    @Test void unsignedTamperedAndReplayRequestsCannotGrant() throws Exception {
        publish(); db.jdbc().update("DELETE FROM knowledge_user_file_permission");
        mvc.perform(post(PATH).contentType("application/json").content(ASSERTION)).andExpect(status().isUnauthorized());
        var tampered = signed("POST", PATH, ASSERTION, "default", "42").content("{}");
        mvc.perform(tampered).andExpect(status().isUnauthorized());
        var first = signed("POST", PATH, ASSERTION, "default", "42");
        mvc.perform(first).andExpect(status().isOk());
        mvc.perform(first).andExpect(status().isUnauthorized());
        assertEquals(1, countGrants());
    }

    @ParameterizedTest @ValueSource(strings = {"other-actor", "other-tenant", "other-project", "file-mismatch", "pending", "changed-collection", "changed-workspace"})
    void evenSignedAdministrativeRequestsCannotGrantAnotherOrChangedFile(String mode) throws Exception {
        publish(); db.jdbc().update("DELETE FROM knowledge_user_file_permission");
        String tenant = mode.equals("other-tenant") ? "tenant-other" : "default";
        String actor = mode.equals("other-actor") ? "admin-other" : "42";
        if (mode.equals("other-project")) db.jdbc().update("UPDATE knowledge_base SET project_code='foreign'");
        if (mode.equals("file-mismatch")) db.jdbc().update("UPDATE knowledge_document_import_job SET file_id='foreign-file'");
        if (mode.equals("pending")) db.jdbc().update("UPDATE knowledge_document_import_job SET status='PARSED'");
        if (mode.equals("changed-collection")) db.jdbc().update("UPDATE knowledge_document_import_job SET vector_collection_name='rebuilt'");
        if (mode.equals("changed-workspace")) db.jdbc().update("UPDATE knowledge_base SET workspace_id='foreign'");
        var failure = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(signed("POST", PATH, ASSERTION, tenant, actor)));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause()); assertEquals(0, countGrants());
    }

    @Test void mismatchedSignedScopeCannotGrantButAnExistingStrongerOwnGrantIsNotRewritten() {
        publish(); db.jdbc().update("DELETE FROM knowledge_user_file_permission");
        assertThrows(IllegalArgumentException.class, () -> access.ensureOwn("file", new DocumentImportAccessContext("default", "42", "default", "foreign", "PROJECT")));
        assertEquals(0, countGrants());
        db.jdbc().update("INSERT INTO knowledge_user_file_permission(user_id,file_id,permission_type,record_generation) VALUES ('42','file','admin',?)", "a".repeat(32));
        assertTrue(access.ensureOwn("file", assertion()).readable());
        assertEquals("admin", db.jdbc().queryForObject("SELECT permission_type FROM knowledge_user_file_permission", String.class));
        assertEquals("a".repeat(32), db.jdbc().queryForObject("SELECT record_generation FROM knowledge_user_file_permission", String.class));
    }

    @ParameterizedTest @ValueSource(strings = {"anonymous", "project-mismatch", "tenant-missing"})
    void publicationWithoutTrustedIdentityOrMatchingResourceRollsBack(String mode) {
        if (mode.equals("anonymous")) db.jdbc().update("UPDATE knowledge_document_import_job SET created_by_actor_id=NULL");
        if (mode.equals("tenant-missing")) db.jdbc().update("UPDATE knowledge_document_import_job SET tenant_id=''");
        if (mode.equals("project-mismatch")) db.jdbc().update("UPDATE knowledge_document_import_job SET project_code='foreign'");
        assertThrows(IllegalArgumentException.class, () -> publish());
        assertEquals(0, countGrants()); assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals("INDEXING", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
    }

    void publish() { tx.executeWithoutResult(status -> metadata.process(context)); }
    int countGrants() { return db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_user_file_permission", Integer.class); }
    DocumentImportAccessContext assertion() { return new DocumentImportAccessContext("default", "42", "default", "orders", "PROJECT"); }
    static MockHttpServletRequestBuilder signed(String method, String path, String body, String tenant, String actor) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(bytes);
        String canonical = InternalServiceHmac.canonical(method, path, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, tenant, actor, timestamp, nonce, digest);
        return request(org.springframework.http.HttpMethod.valueOf(method), path).contentType("application/json").content(bytes)
                .header(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL)
                .header(InternalServiceAuthHeaders.IDENTITY_SOURCE, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION)
                .header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenant).header(InternalServiceAuthHeaders.IDENTITY_USER_ID, actor)
                .header(InternalServiceAuthHeaders.TIMESTAMP, timestamp).header(InternalServiceAuthHeaders.NONCE, nonce)
                .header(InternalServiceAuthHeaders.BODY_SHA256, digest).header(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical));
    }
}
