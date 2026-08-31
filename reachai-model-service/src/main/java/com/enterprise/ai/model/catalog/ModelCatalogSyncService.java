package com.enterprise.ai.model.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ModelCatalogSyncService {

    private final ModelCatalogSourceMapper sourceMapper;
    private final ModelCatalogSyncRunMapper runMapper;
    private final ModelCatalogSnapshotMapper snapshotMapper;
    private final ModelCatalogHttpFetcher fetcher;
    private final ModelCatalogAnalyzer analyzer;
    private final ModelCatalogPublisher publisher;
    private final ModelCatalogSyncProperties properties;

    public void process(ModelCatalogSyncRunEntity run, String leaseToken) {
        ModelCatalogSourceEntity source = sourceMapper.selectById(run.getSourceId());
        if (source == null || !Boolean.TRUE.equals(source.getEnabled())) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SOURCE_UNAVAILABLE", "Catalog source is missing or disabled", false);
        }
        if (runMapper.markRunning(run.getId(), leaseToken) != 1) {
            throw stale(run.getId());
        }
        ModelCatalogHttpFetcher.FetchResult fetched = fetcher.fetch(source);
        String effectiveHash = fetched.contentSha256() == null
                ? source.getLastContentSha256() : fetched.contentSha256();
        if (fetched.notModified()
                || (source.getLastSuccessAt() != null
                && effectiveHash != null
                && effectiveHash.equalsIgnoreCase(source.getLastContentSha256()))) {
            completeNoChange(run, source, leaseToken, fetched, effectiveHash);
            return;
        }

        ModelCatalogSnapshotEntity snapshot = new ModelCatalogSnapshotEntity();
        snapshot.setSourceId(source.getId());
        snapshot.setSourceUrl(source.getSourceUrl());
        snapshot.setContentSha256(fetched.contentSha256());
        snapshot.setContentType(fetched.contentType());
        snapshot.setContentSizeBytes(fetched.contentSizeBytes());
        snapshot.setNormalizedContent(fetched.normalizedContent());
        snapshot.setResponseHeadersJson(fetched.responseHeadersJson());
        snapshot.setParserVersion("STRUCTURED_API".equalsIgnoreCase(source.getSourceKind())
                ? "structured-api-v1" : "official-page-v1");
        snapshot.setFetchedAt(fetched.fetchedAt());
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insertOrResolve(snapshot);
        if (snapshot.getId() == null) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SNAPSHOT_PERSIST_FAILED", "Catalog snapshot id was not generated", true);
        }
        if (runMapper.recordSnapshot(run.getId(), leaseToken, fetched.httpStatus(), fetched.contentSha256(),
                snapshot.getId(), "ANALYZING") != 1) {
            throw stale(run.getId());
        }
        renewOrThrow(run.getId(), leaseToken);
        ModelCatalogAnalysis analysis = analyzer.analyze(source, fetched.normalizedContent());
        if (snapshotMapper.recordAnalysis(snapshot.getId(), analysis.rawAnalysisJson()) != 1) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SNAPSHOT_ANALYSIS_PERSIST_FAILED",
                    "Catalog snapshot analysis could not be persisted",
                    true);
        }
        snapshot.setAnalysisJson(analysis.rawAnalysisJson());
        snapshot.setAnalyzedAt(LocalDateTime.now());
        renewOrThrow(run.getId(), leaseToken);

        ModelCatalogPublisher.PublishSummary summary = publisher.publish(source, run, snapshot, analysis);
        markSourceSuccess(source, fetched, fetched.contentSha256());
        if (runMapper.complete(
                run.getId(), leaseToken, "SUCCESS", fetched.httpStatus(), fetched.contentSha256(), snapshot.getId(),
                analysis.analysisStatus(), summary.candidateCount(), summary.publishedCount(), summary.reviewCount()) != 1) {
            throw stale(run.getId());
        }
    }

    private void completeNoChange(ModelCatalogSyncRunEntity run,
                                  ModelCatalogSourceEntity source,
                                  String leaseToken,
                                  ModelCatalogHttpFetcher.FetchResult fetched,
                                  String effectiveHash) {
        markSourceSuccess(source, fetched, effectiveHash);
        if (runMapper.complete(
                run.getId(), leaseToken, "NO_CHANGE", fetched.httpStatus(), effectiveHash, null,
                "SKIPPED_UNCHANGED", 0, 0, 0) != 1) {
            throw stale(run.getId());
        }
    }

    private void markSourceSuccess(ModelCatalogSourceEntity source,
                                   ModelCatalogHttpFetcher.FetchResult fetched,
                                   String contentSha256) {
        int updated = sourceMapper.markSuccess(source.getId(), LocalDateTime.now(),
                firstText(fetched.etag(), source.getLastHttpEtag()),
                firstText(fetched.lastModified(), source.getLastHttpModified()),
                contentSha256);
        if (updated != 1) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SOURCE_STATE_PERSIST_FAILED",
                    "Catalog source success state could not be persisted",
                    false);
        }
    }

    private void renewOrThrow(Long runId, String leaseToken) {
        LocalDateTime leasedUntil = LocalDateTime.now()
                .plusSeconds(Math.max(60, properties.getLeaseSeconds()));
        if (runMapper.renew(runId, leaseToken, leasedUntil) != 1) {
            throw stale(runId);
        }
    }

    private ModelCatalogSyncException stale(Long runId) {
        return new ModelCatalogSyncException(
                "MODEL_CATALOG_STALE_LEASE", "Catalog sync lease was lost for run " + runId, true);
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
