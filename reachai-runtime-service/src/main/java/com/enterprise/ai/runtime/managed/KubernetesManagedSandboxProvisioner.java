package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedKubernetesApiClient.KubernetesApiException;
import com.enterprise.ai.runtime.managed.ManagedSandboxPolicyRegistry.GitWorkspaceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "reachai.runtime.managed-executor", name = "sandbox-backend",
        havingValue = "kubernetes")
public class KubernetesManagedSandboxProvisioner implements ManagedSandboxProvisioner {

    private static final int SHARED_GROUP_ID = 65_530;
    private static final int BROKER_USER_ID = 65_531;
    private static final int WORKER_USER_ID = 65_532;
    private static final int GIT_INIT_USER_ID = 65_533;
    private static final String BROKER_SOCKET_PATH = "/run/reachai/broker/runtime.sock";

    private static final String GIT_INIT_SCRIPT = """
            set -eu
            umask 0002
            git clone --no-checkout --filter=blob:none -- "$REACHAI_REPOSITORY_URL" /workspace
            git -C /workspace fetch --depth=1 origin "$REACHAI_REPOSITORY_REVISION"
            git -C /workspace checkout --detach "$REACHAI_REPOSITORY_REVISION"
            test "$(git -C /workspace rev-parse HEAD)" = "$REACHAI_REPOSITORY_REVISION"
            git -C /workspace status --porcelain=v1
            """;

    private final ManagedKubernetesApiClient apiClient;
    private final ManagedKubernetesSandboxProperties properties;
    private final ManagedSandboxPolicyRegistry policyRegistry;
    private final ObjectMapper objectMapper;
    private final String namespace;

    public KubernetesManagedSandboxProvisioner(ManagedKubernetesApiClient apiClient,
                                                ManagedKubernetesSandboxProperties properties,
                                                ManagedSandboxPolicyRegistry policyRegistry,
                                                ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.policyRegistry = policyRegistry;
        this.objectMapper = objectMapper;
        this.namespace = dnsLabel(properties.getNamespace(), "Kubernetes namespace");
        validateConfiguration();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public SandboxHandle provision(ProvisioningRequest request) throws Exception {
        GitWorkspaceSource source = policyRegistry.requireWorkspaceSource(request.projectCode());
        requireGitProxySource(source);
        requireClusterPrerequisites(source);
        String acceptanceCommands = policyRegistry.acceptanceCommandsJson(
                request.projectCode(), request.acceptanceProfile());
        String jobName = jobName(request.executionId());
        String secretName = suffixName(jobName, "-worker");
        String networkPolicyName = suffixName(jobName, "-egress");
        SandboxHandle handle = new SandboxHandle(jobName);
        String secretCollection = "/api/v1/namespaces/" + namespace + "/secrets";
        String secretResource = secretCollection + "/" + secretName;
        String networkPolicyCollection = "/apis/networking.k8s.io/v1/namespaces/"
                + namespace + "/networkpolicies";
        String networkPolicyResource = networkPolicyCollection + "/" + networkPolicyName;
        String jobCollection = "/apis/batch/v1/namespaces/" + namespace + "/jobs";
        String jobResource = jobCollection + "/" + jobName;

        for (int attempt = 0; attempt < 2; attempt++) {
            boolean jobCreateAttempted = false;
            try {
                apiClient.create(secretCollection, workerSecret(request, secretName, acceptanceCommands));
                apiClient.create(networkPolicyCollection,
                        networkPolicy(request, networkPolicyName));
                jobCreateAttempted = true;
                apiClient.create(jobCollection, job(request, source, jobName, secretName));
                return handle;
            } catch (KubernetesApiException conflict) {
                if (conflict.status() == 409 && attempt == 0) {
                    // DB still says QUEUED with a newly issued token, so any same-name bundle is stale.
                    deleteBundle(jobResource, networkPolicyResource, secretResource);
                    continue;
                }
                return resolveCreationFailure(
                        handle, conflict, jobCreateAttempted,
                        jobResource, networkPolicyResource, secretResource);
            } catch (RuntimeException creationFailure) {
                return resolveCreationFailure(
                        handle, creationFailure, jobCreateAttempted,
                        jobResource, networkPolicyResource, secretResource);
            }
        }
        throw new IllegalStateException("Managed Sandbox resource bundle retry was exhausted");
    }

    @Override
    public void cleanup(CleanupRequest request) {
        String jobName = dnsLabel(request.sandboxRef(), "sandboxRef");
        if (!jobName.equals(jobName(request.executionId()))) {
            throw new IllegalArgumentException("Managed Sandbox cleanup reference does not match execution");
        }
        deleteBundle(
                "/apis/batch/v1/namespaces/" + namespace + "/jobs/" + jobName,
                "/apis/networking.k8s.io/v1/namespaces/" + namespace + "/networkpolicies/"
                        + suffixName(jobName, "-egress"),
                "/api/v1/namespaces/" + namespace + "/secrets/" + suffixName(jobName, "-worker"));
    }

    private SandboxHandle resolveCreationFailure(
            SandboxHandle handle,
            RuntimeException creationFailure,
            boolean jobCreateAttempted,
            String jobResource,
            String networkPolicyResource,
            String secretResource) throws ManagedSandboxProvisionOutcomeUnknownException {
        if (!jobCreateAttempted
                || creationFailure instanceof KubernetesApiException conflict && conflict.status() == 409) {
            deleteBundle(jobResource, networkPolicyResource, secretResource);
            throw creationFailure;
        }
        try {
            if (apiClient.exists(jobResource)) return handle;
            deleteBundle(jobResource, networkPolicyResource, secretResource);
            throw creationFailure;
        } catch (KubernetesApiException readFailure) {
            if (readFailure == creationFailure) throw creationFailure;
            throw new ManagedSandboxProvisionOutcomeUnknownException(handle);
        } catch (ManagedKubernetesApiClient.KubernetesTransportException readFailure) {
            if (readFailure == creationFailure) throw creationFailure;
            throw new ManagedSandboxProvisionOutcomeUnknownException(handle);
        }
    }

    private void deleteBundle(String jobResource, String networkPolicyResource, String secretResource) {
        apiClient.delete(jobResource);
        apiClient.delete(networkPolicyResource);
        apiClient.delete(secretResource);
    }

    ObjectNode workerSecret(ProvisioningRequest request, String secretName, String acceptanceCommands) {
        ObjectNode secret = objectMapper.createObjectNode();
        secret.put("apiVersion", "v1");
        secret.put("kind", "Secret");
        ObjectNode metadata = secret.putObject("metadata");
        metadata.put("name", secretName);
        metadata.put("namespace", namespace);
        labels(metadata.putObject("labels"), request.executionId());
        secret.put("type", "Opaque");
        secret.putObject("stringData")
                .put("token", request.workerBootstrapToken())
                .put("acceptance-commands.json", acceptanceCommands);
        return secret;
    }

    ObjectNode networkPolicy(ProvisioningRequest request, String networkPolicyName) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("apiVersion", "networking.k8s.io/v1");
        root.put("kind", "NetworkPolicy");
        ObjectNode metadata = root.putObject("metadata");
        metadata.put("name", networkPolicyName);
        metadata.put("namespace", namespace);
        labels(metadata.putObject("labels"), request.executionId());

        ObjectNode spec = root.putObject("spec");
        ObjectNode matchLabels = spec.putObject("podSelector").putObject("matchLabels");
        matchLabels.put("reachai.ai/managed-execution", executionLabel(request.executionId()));
        spec.putArray("policyTypes").add("Ingress").add("Egress");
        spec.putArray("ingress");
        ArrayNode egress = spec.putArray("egress");
        egressToIp(egress, properties.getDnsProxyIp() + "/32", properties.getDnsProxyPort(), "UDP");
        egressToIp(egress, properties.getDnsProxyIp() + "/32", properties.getDnsProxyPort(), "TCP");
        egressToApp(egress,
                properties.getRuntimeEgressNamespace(), properties.getRuntimeEgressApp(),
                properties.getRuntimeEgressPort());
        egressToApp(egress,
                properties.getModelRelayEgressNamespace(), properties.getModelRelayEgressApp(),
                properties.getModelRelayEgressPort());
        egressToApp(egress,
                properties.getGitProxyEgressNamespace(), properties.getGitProxyEgressApp(),
                properties.getGitProxyEgressPort());
        egressToApp(egress,
                properties.getDependencyProxyEgressNamespace(), properties.getDependencyProxyEgressApp(),
                properties.getDependencyProxyEgressPort());
        return root;
    }

    private void egressToIp(ArrayNode egress, String cidr, int port, String protocol) {
        ObjectNode rule = egress.addObject();
        rule.putArray("to").addObject().putObject("ipBlock").put("cidr", cidr);
        rule.putArray("ports").addObject().put("protocol", protocol).put("port", port);
    }

    private void egressToApp(ArrayNode egress, String targetNamespace, String app, int port) {
        ObjectNode rule = egress.addObject();
        ObjectNode peer = rule.putArray("to").addObject();
        peer.putObject("namespaceSelector").putObject("matchLabels")
                .put("kubernetes.io/metadata.name", targetNamespace);
        peer.putObject("podSelector").putObject("matchLabels").put("app", app);
        rule.putArray("ports").addObject().put("protocol", "TCP").put("port", port);
    }

    ObjectNode job(ProvisioningRequest request,
                   GitWorkspaceSource source,
                   String jobName,
                   String secretName) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("apiVersion", "batch/v1");
        root.put("kind", "Job");
        ObjectNode metadata = root.putObject("metadata");
        metadata.put("name", jobName);
        metadata.put("namespace", namespace);
        labels(metadata.putObject("labels"), request.executionId());

        ObjectNode spec = root.putObject("spec");
        spec.put("backoffLimit", 0);
        spec.put("ttlSecondsAfterFinished", bounded(properties.getTtlSecondsAfterFinished(), 60, 86_400));
        spec.put("activeDeadlineSeconds", Math.min(14_700, request.maxWallTimeSeconds() + 300));
        ObjectNode template = spec.putObject("template");
        ObjectNode templateMetadata = template.putObject("metadata");
        labels(templateMetadata.putObject("labels"), request.executionId());
        templateMetadata.putObject("annotations")
                .put("reachai.ai/network-policy-required", "true")
                .put("sidecar.istio.io/inject", "false");

        ObjectNode pod = template.putObject("spec");
        pod.put("restartPolicy", "Never");
        pod.put("automountServiceAccountToken", false);
        pod.put("enableServiceLinks", false);
        pod.put("serviceAccountName", dnsLabel(
                properties.getWorkerServiceAccountName(), "workerServiceAccountName"));
        pod.put("hostUsers", false);
        pod.put("hostNetwork", false);
        pod.put("hostPID", false);
        pod.put("hostIPC", false);
        pod.put("shareProcessNamespace", false);
        pod.put("dnsPolicy", "None");
        ObjectNode dnsConfig = pod.putObject("dnsConfig");
        dnsConfig.putArray("nameservers").add(ipv4(properties.getDnsProxyIp(), "dnsProxyIp"));
        dnsConfig.putArray("options").addObject().put("name", "ndots").put("value", "1");
        pod.put("runtimeClassName", dnsLabel(properties.getRuntimeClassName(), "runtimeClassName"));
        pod.putObject("nodeSelector")
                .put("reachai.ai/sandbox-runtime", properties.getRuntimeClassName());
        pod.putArray("tolerations").addObject()
                .put("key", "reachai.ai/sandbox")
                .put("operator", "Equal")
                .put("value", "true")
                .put("effect", "NoSchedule");
        pod.put("terminationGracePeriodSeconds",
                bounded(properties.getTerminationGracePeriodSeconds(), 1, 120));
        ObjectNode podSecurity = pod.putObject("securityContext");
        podSecurity.put("runAsNonRoot", true);
        podSecurity.put("fsGroup", SHARED_GROUP_ID);
        podSecurity.put("fsGroupChangePolicy", "OnRootMismatch");
        podSecurity.putObject("seccompProfile").put("type", "RuntimeDefault");

        ArrayNode initContainers = pod.putArray("initContainers");
        ObjectNode broker = initContainers.addObject();
        broker.put("name", "runtime-credential-broker");
        broker.put("restartPolicy", "Always");
        broker.put("image", pinnedImage(properties.getWorkerImage(), "workerImage"));
        broker.put("imagePullPolicy", imagePullPolicy());
        broker.putArray("command")
                .add("node")
                .add("/app/dist/src/runtime-credential-broker-cli.js");
        ArrayNode brokerEnv = broker.putArray("env");
        env(brokerEnv, "REACHAI_RUNTIME_BASE_URL",
                httpsUrl(properties.getRuntimeBaseUrl(), "runtimeBaseUrl"));
        env(brokerEnv, "REACHAI_MANAGED_EXECUTION_ID", request.executionId());
        env(brokerEnv, "REACHAI_MANAGED_WORKER_ID", jobName);
        env(brokerEnv, "REACHAI_MANAGED_WORKER_TOKEN_FILE", "/run/reachai/broker-secret/token");
        env(brokerEnv, "REACHAI_MANAGED_RUNTIME_BROKER_SOCKET", BROKER_SOCKET_PATH);
        env(brokerEnv, "HOME", "/run/reachai/broker-tmp");
        brokerMounts(broker.putArray("volumeMounts"));
        ObjectNode startupProbe = broker.putObject("startupProbe");
        startupProbe.putObject("exec").putArray("command")
                .add("node")
                .add("-e")
                .add("const fs=require('node:fs');process.exit(fs.existsSync('"
                        + BROKER_SOCKET_PATH + "')?0:1)");
        startupProbe.put("periodSeconds", 1);
        startupProbe.put("failureThreshold", 30);
        startupProbe.put("timeoutSeconds", 1);
        containerSecurity(broker.putObject("securityContext"), BROKER_USER_ID, SHARED_GROUP_ID);
        brokerResources(broker.putObject("resources"));

        ObjectNode git = initContainers.addObject();
        git.put("name", "workspace-init");
        git.put("image", pinnedImage(properties.getGitInitImage(), "gitInitImage"));
        git.put("imagePullPolicy", imagePullPolicy());
        git.putArray("command").add("/bin/sh").add("-ec").add(GIT_INIT_SCRIPT);
        env(git.putArray("env"), "REACHAI_REPOSITORY_URL", source.repositoryUrl());
        env(git.withArray("env"), "REACHAI_REPOSITORY_REVISION", source.revision());
        env(git.withArray("env"), "HOME", source.credentialSecretName() == null
                ? "/tmp" : "/run/reachai/git-home");
        mounts(git.putArray("volumeMounts"), source.credentialSecretName() != null);
        containerSecurity(git.putObject("securityContext"), GIT_INIT_USER_ID, SHARED_GROUP_ID);
        resources(git.putObject("resources"));

        ObjectNode worker = pod.putArray("containers").addObject();
        worker.put("name", "worker");
        worker.put("image", pinnedImage(properties.getWorkerImage(), "workerImage"));
        worker.put("imagePullPolicy", imagePullPolicy());
        worker.put("workingDir", "/workspace");
        worker.put("stdin", false);
        worker.put("tty", false);
        ArrayNode workerEnv = worker.putArray("env");
        env(workerEnv, "REACHAI_RUNTIME_BASE_URL", httpsUrl(properties.getRuntimeBaseUrl(), "runtimeBaseUrl"));
        env(workerEnv, "REACHAI_MANAGED_EXECUTION_ID", request.executionId());
        env(workerEnv, "REACHAI_MANAGED_WORKER_ID", jobName);
        env(workerEnv, "REACHAI_MANAGED_RUNTIME_BROKER_SOCKET", BROKER_SOCKET_PATH);
        env(workerEnv, "REACHAI_MANAGED_MODEL_RELAY_HOST",
                serviceHost(properties.getModelRelayServiceHost(), "modelRelayServiceHost"));
        env(workerEnv, "REACHAI_MANAGED_MODEL_RELAY_PORT",
                Integer.toString(port(properties.getModelRelayEgressPort(), "modelRelayEgressPort")));
        env(workerEnv, "REACHAI_MANAGED_ACCEPTANCE_COMMANDS_FILE",
                "/run/reachai/acceptance/acceptance-commands.json");
        env(workerEnv, "REACHAI_MANAGED_WORKSPACE_ROOT", "/workspace");
        env(workerEnv, "REACHAI_MANAGED_STATE_DIRECTORY", "/run/reachai/job");
        env(workerEnv, "REACHAI_MANAGED_OUTPUT_DIRECTORY", "/run/reachai/output");
        env(workerEnv, "REACHAI_CODEX_CONFIG_FILE", "/run/reachai/codex-config/config.toml");
        env(workerEnv, "HOME", "/run/reachai/codex-home");
        workerMounts(worker.putArray("volumeMounts"));
        containerSecurity(worker.putObject("securityContext"), WORKER_USER_ID, SHARED_GROUP_ID);
        resources(worker.putObject("resources"));

        volumes(pod.putArray("volumes"), secretName, source.credentialSecretName());
        return root;
    }

    private void volumes(ArrayNode volumes, String workerSecretName, String gitCredentialSecretName) {
        emptyDir(volumes, "workspace", properties.getWorkspaceSizeLimit());
        emptyDir(volumes, "job-state", "64Mi");
        emptyDir(volumes, "output", "128Mi");
        emptyDir(volumes, "codex-home", "1Gi");
        emptyDir(volumes, "tmp", "512Mi");
        emptyDir(volumes, "broker-socket", "1Mi");
        emptyDir(volumes, "broker-tmp", "16Mi");
        secretProjection(volumes, "broker-secret", workerSecretName, "token", "token");
        secretProjection(volumes, "acceptance-config", workerSecretName,
                "acceptance-commands.json", "acceptance-commands.json");
        ObjectNode codexConfig = volumes.addObject();
        codexConfig.put("name", "codex-config");
        codexConfig.putObject("configMap")
                .put("name", dnsLabel(properties.getCodexConfigMapName(), "codexConfigMapName"))
                .put("defaultMode", 292);
        if (gitCredentialSecretName != null) {
            ObjectNode gitAuth = volumes.addObject();
            gitAuth.put("name", "git-auth");
            ObjectNode secret = gitAuth.putObject("secret");
            secret.put("secretName", dnsLabel(gitCredentialSecretName, "Git credential Secret"));
            secret.put("defaultMode", 288);
            secret.putArray("items").addObject().put("key", "netrc").put("path", ".netrc");
        }
    }

    private void mounts(ArrayNode mounts, boolean gitAuth) {
        mount(mounts, "workspace", "/workspace", false);
        mount(mounts, "tmp", "/tmp", false);
        if (gitAuth) mount(mounts, "git-auth", "/run/reachai/git-home", true);
    }

    private void brokerMounts(ArrayNode mounts) {
        mount(mounts, "broker-secret", "/run/reachai/broker-secret", true);
        mount(mounts, "broker-socket", "/run/reachai/broker", false);
        mount(mounts, "broker-tmp", "/run/reachai/broker-tmp", false);
    }

    private void workerMounts(ArrayNode mounts) {
        mount(mounts, "workspace", "/workspace", false);
        mount(mounts, "job-state", "/run/reachai/job", false);
        mount(mounts, "output", "/run/reachai/output", false);
        mount(mounts, "codex-home", "/run/reachai/codex-home", false);
        mount(mounts, "tmp", "/tmp", false);
        mount(mounts, "broker-socket", "/run/reachai/broker", true);
        mount(mounts, "acceptance-config", "/run/reachai/acceptance", true);
        mount(mounts, "codex-config", "/run/reachai/codex-config", true);
    }

    private void secretProjection(ArrayNode volumes, String volumeName, String secretName,
                                  String key, String projectedPath) {
        ObjectNode volume = volumes.addObject();
        volume.put("name", volumeName);
        ObjectNode secret = volume.putObject("secret");
        secret.put("secretName", secretName);
        secret.put("defaultMode", 288);
        secret.putArray("items").addObject().put("key", key).put("path", projectedPath);
    }

    private void resources(ObjectNode resources) {
        ObjectNode requests = resources.putObject("requests");
        requests.put("cpu", quantity(properties.getCpuRequest(), "cpuRequest"));
        requests.put("memory", quantity(properties.getMemoryRequest(), "memoryRequest"));
        requests.put("ephemeral-storage", quantity(properties.getEphemeralStorageRequest(), "ephemeralStorageRequest"));
        ObjectNode limits = resources.putObject("limits");
        limits.put("cpu", quantity(properties.getCpuLimit(), "cpuLimit"));
        limits.put("memory", quantity(properties.getMemoryLimit(), "memoryLimit"));
        limits.put("ephemeral-storage", quantity(properties.getEphemeralStorageLimit(), "ephemeralStorageLimit"));
    }

    private void brokerResources(ObjectNode resources) {
        ObjectNode requests = resources.putObject("requests");
        requests.put("cpu", quantity(properties.getBrokerCpuRequest(), "brokerCpuRequest"));
        requests.put("memory", quantity(properties.getBrokerMemoryRequest(), "brokerMemoryRequest"));
        requests.put("ephemeral-storage", quantity(properties.getBrokerEphemeralStorageRequest(),
                "brokerEphemeralStorageRequest"));
        ObjectNode limits = resources.putObject("limits");
        limits.put("cpu", quantity(properties.getBrokerCpuLimit(), "brokerCpuLimit"));
        limits.put("memory", quantity(properties.getBrokerMemoryLimit(), "brokerMemoryLimit"));
        limits.put("ephemeral-storage", quantity(properties.getBrokerEphemeralStorageLimit(),
                "brokerEphemeralStorageLimit"));
    }

    private void containerSecurity(ObjectNode security, int runAsUser, int runAsGroup) {
        security.put("runAsNonRoot", true);
        security.put("runAsUser", runAsUser);
        security.put("runAsGroup", runAsGroup);
        security.put("allowPrivilegeEscalation", false);
        security.put("privileged", false);
        security.put("readOnlyRootFilesystem", true);
        security.put("procMount", "Default");
        security.putObject("capabilities").putArray("drop").add("ALL");
        security.putObject("seccompProfile").put("type", "RuntimeDefault");
        security.putObject("appArmorProfile").put("type", "RuntimeDefault");
    }

    private void labels(ObjectNode labels, String executionId) {
        labels.put("app.kubernetes.io/name", "reachai-managed-executor");
        labels.put("app.kubernetes.io/component", "worker");
        labels.put("reachai.ai/managed-execution", executionLabel(executionId));
    }

    private String executionLabel(String executionId) {
        return sha256(executionId).substring(0, 32);
    }

    private void emptyDir(ArrayNode volumes, String name, String sizeLimit) {
        ObjectNode volume = volumes.addObject();
        volume.put("name", name);
        volume.putObject("emptyDir").put("sizeLimit", quantity(sizeLimit, name + " sizeLimit"));
    }

    private void mount(ArrayNode mounts, String name, String mountPath, boolean readOnly) {
        mounts.addObject().put("name", name).put("mountPath", mountPath).put("readOnly", readOnly);
    }

    private void env(ArrayNode environment, String name, String value) {
        environment.addObject().put("name", name).put("value", value);
    }

    private String jobName(String executionId) {
        String safe = executionId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (safe.isBlank()) safe = "execution";
        if (safe.length() > 32) safe = safe.substring(safe.length() - 32);
        safe = safe.replaceAll("-$", "");
        return dnsLabel("reachai-mex-" + safe + "-" + sha256(executionId).substring(0, 8), "jobName");
    }

    private String suffixName(String base, String suffix) {
        int maximumBase = 63 - suffix.length();
        String candidate = (base.length() > maximumBase ? base.substring(0, maximumBase) : base)
                .replaceAll("-$", "") + suffix;
        return dnsLabel(candidate, "resourceName");
    }

    private void requireGitProxySource(GitWorkspaceSource source) {
        String configuredHost = serviceHost(properties.getGitProxyServiceHost(), "gitProxyServiceHost");
        String sourceHost;
        try {
            sourceHost = URI.create(source.repositoryUrl()).getHost();
        } catch (RuntimeException invalid) {
            sourceHost = null;
        }
        if (sourceHost == null || !configuredHost.equalsIgnoreCase(sourceHost)) {
            throw new ManagedExecutionException(503, "MANAGED_GIT_PROXY_REQUIRED",
                    "Managed workspace source must use the configured Git egress proxy");
        }
    }

    private void requireClusterPrerequisites(GitWorkspaceSource source) {
        requireExists("/apis/networking.k8s.io/v1/namespaces/" + namespace
                + "/networkpolicies/" + dnsLabel(properties.getStaticDefaultDenyPolicyName(),
                "staticDefaultDenyPolicyName"));
        requireExists("/apis/node.k8s.io/v1/runtimeclasses/"
                + dnsLabel(properties.getRuntimeClassName(), "runtimeClassName"));
        requireExists("/api/v1/namespaces/" + namespace + "/serviceaccounts/"
                + dnsLabel(properties.getWorkerServiceAccountName(), "workerServiceAccountName"));
        requireExists("/api/v1/namespaces/" + namespace + "/configmaps/"
                + dnsLabel(properties.getCodexConfigMapName(), "codexConfigMapName"));
        if (source.credentialSecretName() != null) {
            requireExists("/api/v1/namespaces/" + namespace + "/secrets/"
                    + dnsLabel(source.credentialSecretName(), "Git credential Secret"));
        }
    }

    private void requireExists(String resourcePath) {
        if (!apiClient.exists(resourcePath)) {
            throw new ManagedExecutionException(503, "MANAGED_SANDBOX_PREREQUISITE_MISSING",
                    "Managed Sandbox prerequisite is not installed");
        }
    }

    private void validateConfiguration() {
        if (!properties.isNetworkPolicyEnabled()) {
            throw new IllegalStateException("Managed Kubernetes NetworkPolicy must be enabled");
        }
        pinnedImage(properties.getWorkerImage(), "workerImage");
        pinnedImage(properties.getGitInitImage(), "gitInitImage");
        dnsLabel(properties.getCodexConfigMapName(), "codexConfigMapName");
        dnsLabel(properties.getWorkerServiceAccountName(), "workerServiceAccountName");
        dnsLabel(properties.getStaticDefaultDenyPolicyName(), "staticDefaultDenyPolicyName");
        String runtimeClass = dnsLabel(properties.getRuntimeClassName(), "runtimeClassName");
        Set<String> allowedRuntimeClasses = new HashSet<>();
        if (StringUtils.hasText(properties.getAllowedRuntimeClasses())) {
            for (String candidate : properties.getAllowedRuntimeClasses().split(",")) {
                if (StringUtils.hasText(candidate)) {
                    allowedRuntimeClasses.add(dnsLabel(candidate.trim(), "allowedRuntimeClasses"));
                }
            }
        }
        if (!allowedRuntimeClasses.contains(runtimeClass)) {
            throw new IllegalStateException("Managed Kubernetes runtimeClassName is not allowlisted");
        }
        String runtimeBaseUrl = httpsUrl(properties.getRuntimeBaseUrl(), "runtimeBaseUrl");
        String runtimeHost = serviceHost(properties.getRuntimeServiceHost(), "runtimeServiceHost");
        URI runtimeUri = URI.create(runtimeBaseUrl);
        if (!runtimeHost.equalsIgnoreCase(runtimeUri.getHost())
                || effectiveHttpsPort(runtimeUri) != properties.getRuntimeEgressPort()) {
            throw new IllegalStateException(
                    "Managed Kubernetes runtimeBaseUrl must match runtimeServiceHost and runtimeEgressPort");
        }
        validateEgressTarget(
                properties.getRuntimeEgressNamespace(), properties.getRuntimeEgressApp(),
                properties.getRuntimeEgressPort(), "runtimeEgress");
        validateEgressTarget(
                properties.getModelRelayEgressNamespace(), properties.getModelRelayEgressApp(),
                properties.getModelRelayEgressPort(), "modelRelayEgress");
        validateEgressTarget(
                properties.getGitProxyEgressNamespace(), properties.getGitProxyEgressApp(),
                properties.getGitProxyEgressPort(), "gitProxyEgress");
        validateEgressTarget(
                properties.getDependencyProxyEgressNamespace(), properties.getDependencyProxyEgressApp(),
                properties.getDependencyProxyEgressPort(), "dependencyProxyEgress");
        serviceHost(properties.getModelRelayServiceHost(), "modelRelayServiceHost");
        serviceHost(properties.getGitProxyServiceHost(), "gitProxyServiceHost");
        serviceHost(properties.getDependencyProxyServiceHost(), "dependencyProxyServiceHost");
        ipv4(properties.getDnsProxyIp(), "dnsProxyIp");
        port(properties.getDnsProxyPort(), "dnsProxyPort");
        imagePullPolicy();
    }

    private void validateEgressTarget(String targetNamespace, String app, int targetPort, String field) {
        dnsLabel(targetNamespace, field + "Namespace");
        dnsLabel(app, field + "App");
        port(targetPort, field + "Port");
    }

    private String pinnedImage(String value, String field) {
        if (!StringUtils.hasText(value)
                || !value.matches("[A-Za-z0-9._/:@-]+@sha256:[a-f0-9]{64}")) {
            throw new IllegalStateException("Managed Kubernetes " + field + " must use an image digest");
        }
        return value;
    }

    private String httpsUrl(String value, String field) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            if (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath())) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString().replaceAll("/$", "");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Managed Kubernetes " + field + " must be credential-free HTTPS");
        }
    }

    private String imagePullPolicy() {
        String value = properties.getImagePullPolicy();
        if (!Map.of("Always", true, "IfNotPresent", true, "Never", true).containsKey(value)) {
            throw new IllegalStateException("Managed Kubernetes imagePullPolicy is invalid");
        }
        return value;
    }

    private String dnsLabel(String value, String field) {
        if (!StringUtils.hasText(value) || value.length() > 63
                || !value.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalStateException("Managed Kubernetes " + field + " is invalid");
        }
        return value;
    }

    private String serviceHost(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Managed Kubernetes " + field + " is invalid");
        }
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.length() > 253 || host.endsWith(".") || host.contains("..")) {
            throw new IllegalStateException("Managed Kubernetes " + field + " is invalid");
        }
        for (String label : host.split("\\.")) dnsLabel(label, field);
        return host;
    }

    private String ipv4(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Managed Kubernetes " + field + " is invalid");
        }
        String[] octets = value.trim().split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalStateException("Managed Kubernetes " + field + " is invalid");
        }
        for (String octet : octets) {
            try {
                if (octet.isEmpty() || octet.length() > 3
                        || Integer.parseInt(octet) < 0 || Integer.parseInt(octet) > 255) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Managed Kubernetes " + field + " is invalid");
            }
        }
        return value.trim();
    }

    private int port(int value, String field) {
        if (value < 1 || value > 65_535) {
            throw new IllegalStateException("Managed Kubernetes " + field + " is invalid");
        }
        return value;
    }

    private int effectiveHttpsPort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private String quantity(String value, String field) {
        if (!StringUtils.hasText(value) || value.length() > 32
                || !value.matches("[0-9]+(?:m|Ki|Mi|Gi|Ti)?")) {
            throw new IllegalStateException("Managed Kubernetes " + field + " is invalid");
        }
        return value;
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
