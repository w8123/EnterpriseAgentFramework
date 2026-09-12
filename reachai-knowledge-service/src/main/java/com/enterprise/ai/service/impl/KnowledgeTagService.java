package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.domain.dto.KnowledgeTagBatchRequest;
import com.enterprise.ai.domain.dto.KnowledgeTagDTO;
import com.enterprise.ai.domain.dto.KnowledgeTagRequest;
import com.enterprise.ai.domain.dto.KnowledgeTagStatsDTO;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.KnowledgeTag;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.repository.KnowledgeTagRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/** Tag references are created and retired while their owning knowledge base is locked. */
@Service
public class KnowledgeTagService {
    private final KnowledgeBaseRepository bases;
    private final FileInfoRepository files;
    private final ChunkRepository chunks;
    private final KnowledgeTagRepository tags;
    private final TransactionTemplate transactions;

    public KnowledgeTagService(KnowledgeBaseRepository bases, FileInfoRepository files, ChunkRepository chunks,
                               KnowledgeTagRepository tags, PlatformTransactionManager manager) {
        this.bases = bases; this.files = files; this.chunks = chunks; this.tags = tags;
        transactions = new TransactionTemplate(manager);
    }

    public List<KnowledgeTagDTO> list(String code, String targetType, String targetId) {
        var kb = new KnowledgeBaseLookup(bases).requireByCode(code);
        var query = new LambdaQueryWrapper<KnowledgeTag>().eq(KnowledgeTag::getKnowledgeBaseId, kb.getId())
                .orderByAsc(KnowledgeTag::getTagGroup, KnowledgeTag::getSortOrder).orderByDesc(KnowledgeTag::getCreateTime);
        String type = targetType == null || targetType.isBlank() ? null : type(targetType);
        if (type != null) query.eq(KnowledgeTag::getTargetType, type);
        if (targetId != null && !targetId.isBlank()) {
            if ("KNOWLEDGE".equals(type)) {
                knowledgeTarget(kb, targetId);
                query.isNull(KnowledgeTag::getTargetId);
            } else query.eq(KnowledgeTag::getTargetId, "CHUNK".equals(type) ? chunkId(targetId) : targetId.trim());
        }
        return tags.selectList(query).stream().map(this::dto).toList();
    }

    public List<KnowledgeTagStatsDTO> stats(String code) {
        var kb = new KnowledgeBaseLookup(bases).requireByCode(code);
        var grouped = tags.selectList(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getKnowledgeBaseId, kb.getId()).orderByAsc(KnowledgeTag::getId))
                .stream().collect(Collectors.groupingBy(tag -> new TagKey(tag.getTagKey(), tag.getTagValue())));
        return grouped.values().stream().map(items -> {
            var first = items.get(0);
            return KnowledgeTagStatsDTO.builder().tagKey(first.getTagKey()).tagValue(first.getTagValue())
                    .tagGroup(first.getTagGroup()).color(first.getColor()).description(first.getDescription())
                    .parentId(first.getParentId()).sortOrder(first.getSortOrder()).totalCount(items.size())
                    .knowledgeCount(count(items, "KNOWLEDGE")).fileCount(count(items, "FILE"))
                    .chunkCount(count(items, "CHUNK")).build();
        }).sorted(Comparator.comparing((KnowledgeTagStatsDTO item) -> fallback(item.getTagGroup(), "默认"))
                .thenComparing(item -> item.getSortOrder() == null ? 0 : item.getSortOrder())
                .thenComparing(KnowledgeTagStatsDTO::getTagKey).thenComparing(KnowledgeTagStatsDTO::getTagValue)).toList();
    }

    public KnowledgeTagDTO create(String code, KnowledgeTagRequest request) {
        validate(request);
        String type = type(request.getTargetType());
        return transactions.execute(status -> {
            var kb = lock(code);
            String target;
            if ("KNOWLEDGE".equals(type)) {
                target = knowledgeTarget(kb, request.getTargetId());
            } else {
                target = resolve(kb.getId(), type, Collections.singletonList(request.getTargetId())).get(0);
            }
            parent(kb.getId(), request.getParentId());
            var existing = existing(kb.getId(), type, target, request);
            return dto(existing == null ? insert(kb.getId(), type, target, request) : existing);
        });
    }

    public List<KnowledgeTagDTO> createBatch(String code, KnowledgeTagBatchRequest request) {
        if (request == null) throw new IllegalArgumentException("标签请求不能为空");
        var values = new KnowledgeTagRequest(); BeanUtils.copyProperties(request, values);
        validate(values);
        String type = type(request.getTargetType());
        if ("KNOWLEDGE".equals(type)) throw new IllegalArgumentException("批量标签仅支持文件或片段");
        return transactions.execute(status -> {
            var kb = lock(code);
            var targets = resolve(kb.getId(), type, request.getTargetIds());
            parent(kb.getId(), request.getParentId());
            var created = new ArrayList<KnowledgeTagDTO>();
            for (String target : targets) {
                if (existing(kb.getId(), type, target, values) == null) created.add(dto(insert(kb.getId(), type, target, values)));
            }
            return created;
        });
    }

    public void delete(String code, Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("标签ID无效");
        transactions.executeWithoutResult(status -> {
            var kb = lock(code);
            var tag = tags.selectOne(new LambdaQueryWrapper<KnowledgeTag>()
                    .eq(KnowledgeTag::getKnowledgeBaseId, kb.getId()).eq(KnowledgeTag::getId, id).last("FOR UPDATE"));
            if (tag != null) remove(kb.getId(), List.of(tag.getId()));
        });
    }

    /** Called before replacing or deleting file/chunk metadata in its existing transaction. */
    public void retireFile(Long knowledgeBaseId, String fileId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("文件标签退场必须与文件元数据处于同一事务");
        }
        if (knowledgeBaseId == null || fileId == null || fileId.isBlank() || bases.lockById(knowledgeBaseId) == null) {
            throw new IllegalArgumentException("标签退场目标无效");
        }
        var ids = tags.selectList(new LambdaQueryWrapper<KnowledgeTag>().select(KnowledgeTag::getId)
                .eq(KnowledgeTag::getKnowledgeBaseId, knowledgeBaseId).eq(KnowledgeTag::getTargetType, "FILE")
                .eq(KnowledgeTag::getTargetId, fileId).orderByAsc(KnowledgeTag::getId).last("FOR UPDATE"))
                .stream().map(KnowledgeTag::getId).toList();
        remove(knowledgeBaseId, ids);
        var chunkIds = chunks.selectList(new LambdaQueryWrapper<Chunk>().select(Chunk::getId)
                .eq(Chunk::getKnowledgeBaseId, knowledgeBaseId).eq(Chunk::getFileId, fileId)
                .orderByAsc(Chunk::getId).last("FOR UPDATE")).stream().map(chunk -> chunk.getId().toString()).toList();
        for (int start = 0; start < chunkIds.size(); start += 500) {
            var targets = chunkIds.subList(start, Math.min(start + 500, chunkIds.size()));
            var tagIds = tags.selectList(new LambdaQueryWrapper<KnowledgeTag>().select(KnowledgeTag::getId)
                    .eq(KnowledgeTag::getKnowledgeBaseId, knowledgeBaseId).eq(KnowledgeTag::getTargetType, "CHUNK")
                    .in(KnowledgeTag::getTargetId, targets).orderByAsc(KnowledgeTag::getId).last("FOR UPDATE"))
                    .stream().map(KnowledgeTag::getId).toList();
            remove(knowledgeBaseId, tagIds);
        }
    }

    private KnowledgeBase lock(String code) {
        var expected = new KnowledgeBaseLookup(bases).requireByCode(code);
        var current = bases.lockById(expected.getId());
        if (current == null || !Objects.equals(current.getCode(), expected.getCode())) {
            throw new IllegalStateException("原知识库已删除或重建");
        }
        return current;
    }

    private List<String> resolve(Long kb, String type, List<String> requested) {
        if (requested == null) throw new IllegalArgumentException("标签目标不能为空");
        var normalized = requested.stream().filter(Objects::nonNull).map(String::trim).filter(id -> !id.isBlank())
                .map(id -> "CHUNK".equals(type) ? chunkId(id) : bounded(id, 128, "文件ID"))
                .distinct().toList();
        if (normalized.isEmpty()) throw new IllegalArgumentException("标签目标不能为空");
        var resolved = new ArrayList<String>();
        for (int start = 0; start < normalized.size(); start += 500) {
            var page = normalized.subList(start, Math.min(start + 500, normalized.size()));
            // Locking reads see deletions committed during the knowledge-base lock wait under MySQL RR.
            List<String> actual = "FILE".equals(type)
                    ? files.selectList(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getKnowledgeBaseId, kb)
                        .in(FileInfo::getFileId, page).orderByAsc(FileInfo::getId).last("FOR UPDATE"))
                        .stream().map(FileInfo::getFileId).toList()
                    : chunks.selectList(new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, kb)
                        .in(Chunk::getId, page.stream().map(Long::valueOf).toList()).orderByAsc(Chunk::getId).last("FOR UPDATE"))
                        .stream().map(chunk -> chunk.getId().toString()).toList();
            if (actual.size() != page.size()) throw new IllegalArgumentException("标签目标不存在或不属于当前知识库");
            resolved.addAll(actual);
        }
        return resolved.stream().distinct().toList();
    }

    private void parent(Long kb, Long id) {
        if (id == null) return;
        if (id <= 0 || tags.selectOne(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getKnowledgeBaseId, kb).eq(KnowledgeTag::getId, id).last("FOR UPDATE")) == null) {
            throw new IllegalArgumentException("父标签不存在或不属于当前知识库");
        }
    }

    private KnowledgeTag existing(Long kb, String type, String target, KnowledgeTagRequest request) {
        var query = new LambdaQueryWrapper<KnowledgeTag>().eq(KnowledgeTag::getKnowledgeBaseId, kb)
                .eq(KnowledgeTag::getTargetType, type).eq(KnowledgeTag::getTagKey, request.getTagKey())
                .eq(KnowledgeTag::getTagValue, request.getTagValue()).orderByAsc(KnowledgeTag::getId).last("LIMIT 1 FOR UPDATE");
        if (target == null) query.isNull(KnowledgeTag::getTargetId); else query.eq(KnowledgeTag::getTargetId, target);
        return tags.selectOne(query);
    }

    private KnowledgeTag insert(Long kb, String type, String target, KnowledgeTagRequest request) {
        var tag = new KnowledgeTag(); tag.setKnowledgeBaseId(kb); tag.setTargetType(type); tag.setTargetId(target);
        tag.setTagKey(request.getTagKey()); tag.setTagValue(request.getTagValue());
        tag.setTagGroup(fallback(request.getTagGroup(), "默认")); tag.setColor(fallback(request.getColor(), "#409EFF"));
        tag.setDescription(request.getDescription()); tag.setParentId(request.getParentId());
        tag.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        if (tags.insert(tag) != 1) throw new IllegalStateException("标签写入失败");
        return tag;
    }

    private void remove(Long kb, List<Long> ids) {
        for (int start = 0; start < ids.size(); start += 500) {
            var page = ids.subList(start, Math.min(start + 500, ids.size()));
            tags.update(null, new LambdaUpdateWrapper<KnowledgeTag>().eq(KnowledgeTag::getKnowledgeBaseId, kb)
                    .in(KnowledgeTag::getParentId, page).set(KnowledgeTag::getParentId, null));
            if (tags.delete(new LambdaQueryWrapper<KnowledgeTag>().eq(KnowledgeTag::getKnowledgeBaseId, kb)
                    .in(KnowledgeTag::getId, page)) != page.size()) throw new IllegalStateException("标签退场数量不一致");
        }
    }

    private void validate(KnowledgeTagRequest request) {
        if (request == null) throw new IllegalArgumentException("标签请求不能为空");
        bounded(request.getTagKey(), 64, "标签名称"); bounded(request.getTagValue(), 128, "标签值");
        bounded(fallback(request.getTagGroup(), "默认"), 64, "标签组");
        bounded(fallback(request.getColor(), "#409EFF"), 32, "标签颜色");
        if (request.getDescription() != null && request.getDescription().length() > 512) throw new IllegalArgumentException("标签说明过长");
    }

    private String bounded(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(label + "为空或过长");
        return value;
    }

    private String type(String input) {
        String type = fallback(input, "KNOWLEDGE").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("KNOWLEDGE", "FILE", "CHUNK").contains(type)) throw new IllegalArgumentException("标签目标类型无效");
        return type;
    }

    private String chunkId(String input) {
        try {
            String value = input.trim();
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return Long.toString(id);
        } catch (NumberFormatException failure) { throw new IllegalArgumentException("片段目标必须是有效的正整数ID"); }
    }

    private String knowledgeTarget(KnowledgeBase kb, String requested) {
        if (requested != null && !requested.isBlank() && !requested.trim().equals(kb.getCode())
                && !requested.trim().equals(kb.getId().toString())) {
            throw new IllegalArgumentException("知识库标签不能指定其他目标");
        }
        return null;
    }

    private KnowledgeTagDTO dto(KnowledgeTag tag) { var result = new KnowledgeTagDTO(); BeanUtils.copyProperties(tag, result); return result; }
    private int count(List<KnowledgeTag> items, String type) { return (int) items.stream().filter(tag -> type.equals(tag.getTargetType())).count(); }
    private String fallback(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private record TagKey(String key, String value) { }
}
