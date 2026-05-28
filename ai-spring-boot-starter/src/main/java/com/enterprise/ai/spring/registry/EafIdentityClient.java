package com.enterprise.ai.spring.registry;

import java.util.List;
import java.util.Map;

public class EafIdentityClient {

    private final EafRegistryClient registryClient;

    public EafIdentityClient(EafRegistryClient registryClient) {
        this.registryClient = registryClient;
    }

    public EafUserSyncResult upsertUser(EafExternalUser user) {
        return registryClient.upsertExternalUser(user);
    }

    public EafUserSyncResult disableUser(String externalUserId) {
        return registryClient.disableExternalUser(externalUserId);
    }

    public EafUserSyncResult deleteUser(String externalUserId) {
        return registryClient.deleteExternalUser(externalUserId);
    }

    public EafBatchUserSyncResult syncUsers(List<EafExternalUser> users) {
        return registryClient.syncExternalUsers(users == null ? List.of() : users);
    }

    static Map<String, Object> externalUserBody(EafExternalUser user) {
        return Map.of(
                "globalUserId", nullToBlank(user.globalUserId()),
                "externalUserId", nullToBlank(user.externalUserId()),
                "userName", nullToBlank(user.userName()),
                "email", nullToBlank(user.email()),
                "mobile", nullToBlank(user.mobile()),
                "deptId", nullToBlank(user.deptId()),
                "deptName", nullToBlank(user.deptName()),
                "roles", user.roles(),
                "attributes", user.attributes()
        );
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
