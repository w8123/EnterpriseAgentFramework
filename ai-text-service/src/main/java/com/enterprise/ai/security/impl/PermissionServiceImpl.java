package com.enterprise.ai.security.impl;

import com.enterprise.ai.repository.UserFilePermissionRepository;
import com.enterprise.ai.security.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private static final String PERMISSION_CACHE_PREFIX = "perm:files:";
    private static final long CACHE_TTL_MINUTES = 10;

    private final UserFilePermissionRepository permissionRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getAccessibleFileIds(String userId) {
        String cacheKey = PERMISSION_CACHE_PREFIX + userId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof List) {
            log.debug("权限缓存命中 userId={}", userId);
            return (List<String>) cached;
        }

        List<String> fileIds = permissionRepository.selectFileIdsByUserId(userId);
        if (fileIds != null && !fileIds.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, fileIds, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }
        log.debug("查询用户权限 userId={}, fileCount={}", userId, fileIds == null ? 0 : fileIds.size());
        return fileIds;
    }

    @Override
    public String buildMilvusFilter(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return "file_id in [\"\"]";
        }
        String ids = fileIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(", "));
        return "file_id in [" + ids + "]";
    }
}
