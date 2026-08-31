package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedSandboxProvisioner.ProvisioningRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class KubernetesManagedSandboxProvisionerTest {

    private static final String DIGEST = "@sha256:" + "a".repeat(64);

    @Test
    void createsADigestPinnedHardenedJobWithATokenBrokerAndNoWorkerCredentials() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ManagedKubernetesApiClient api = mock(ManagedKubernetesApiClient.class);
        when(api.exists(anyString())).thenReturn(true);
        KubernetesManagedSandboxProvisioner provisioner = new KubernetesManagedSandboxProvisioner(
                api,
                properties(),
                registry(objectMapper),
                objectMapper);
        ProvisioningRequest request = request();

        ManagedSandboxProvisioner.SandboxHandle handle = provisioner.provision(request);

        assertThat(handle.sandboxRef()).startsWith("reachai-mex-");
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JsonNode> bodies = ArgumentCaptor.forClass(JsonNode.class);
        verify(api, times(3)).create(paths.capture(), bodies.capture());
        assertThat(paths.getAllValues()).containsExactly(
                "/api/v1/namespaces/reachai-sandbox/secrets",
                "/apis/networking.k8s.io/v1/namespaces/reachai-sandbox/networkpolicies",
                "/apis/batch/v1/namespaces/reachai-sandbox/jobs");

        JsonNode secret = bodies.getAllValues().get(0);
        JsonNode networkPolicy = bodies.getAllValues().get(1);
        JsonNode job = bodies.getAllValues().get(2);
        assertThat(secret.path("stringData").path("token").asText())
                .isEqualTo(request.workerBootstrapToken());
        assertThat(secret.path("stringData").path("acceptance-commands.json").asText())
                .contains("mvn", "test")
                .doesNotContain(request.workerBootstrapToken());

        JsonNode pod = job.at("/spec/template/spec");
        assertThat(pod.path("automountServiceAccountToken").asBoolean()).isFalse();
        assertThat(pod.path("serviceAccountName").asText()).isEqualTo("reachai-managed-worker");
        assertThat(pod.path("hostUsers").asBoolean(true)).isFalse();
        assertThat(pod.path("dnsPolicy").asText()).isEqualTo("None");
        assertThat(pod.at("/dnsConfig/nameservers/0").asText()).isEqualTo("10.96.0.53");
        assertThat(pod.path("runtimeClassName").asText()).isEqualTo("gvisor");
        JsonNode worker = pod.path("containers").get(0);
        JsonNode broker = pod.path("initContainers").get(0);
        JsonNode workspaceInit = pod.path("initContainers").get(1);
        assertThat(worker.path("image").asText()).endsWith(DIGEST);
        assertThat(broker.path("image").asText()).endsWith(DIGEST);
        assertThat(workspaceInit.path("image").asText()).endsWith(DIGEST);
        assertThat(workspaceInit.at("/command/2").asText()).contains("umask 0002");
        assertThat(broker.path("name").asText()).isEqualTo("runtime-credential-broker");
        assertThat(broker.path("restartPolicy").asText()).isEqualTo("Always");
        assertThat(broker.at("/startupProbe/exec/command").toString()).contains("runtime.sock");
        assertThat(pod.at("/securityContext/fsGroup").asInt()).isEqualTo(65_530);
        assertThat(broker.at("/securityContext/runAsUser").asInt()).isEqualTo(65_531);
        assertThat(worker.at("/securityContext/runAsUser").asInt()).isEqualTo(65_532);
        assertThat(workspaceInit.at("/securityContext/runAsUser").asInt()).isEqualTo(65_533);
        assertThat(worker.at("/securityContext/readOnlyRootFilesystem").asBoolean())
                .isTrue();
        assertThat(worker.at("/securityContext/capabilities/drop/0").asText())
                .isEqualTo("ALL");
        assertThat(worker.at("/securityContext/appArmorProfile/type").asText())
                .isEqualTo("RuntimeDefault");
        assertThat(workspaceInit.path("resources").path("limits").path("memory").asText())
                .isEqualTo("4Gi");
        assertThat(broker.path("resources").path("limits").path("memory").asText())
                .isEqualTo("256Mi");

        assertThat(networkPolicy.at("/spec/policyTypes").toString())
                .contains("Ingress", "Egress");
        assertThat(networkPolicy.at("/spec/ingress").isEmpty()).isTrue();
        assertThat(networkPolicy.at("/spec/egress").size()).isEqualTo(6);
        assertThat(networkPolicy.toString())
                .contains("10.96.0.53/32", "reachai-runtime-service", "reachai-model-relay",
                        "reachai-git-proxy", "reachai-dependency-proxy")
                .doesNotContain("0.0.0.0/0", "169.254.169.254");

        List<String> workerMounts = values(
                worker.path("volumeMounts"), "name");
        List<String> brokerMounts = values(broker.path("volumeMounts"), "name");
        List<String> gitInitMounts = values(workspaceInit.path("volumeMounts"), "name");
        assertThat(workerMounts)
                .contains("broker-socket", "acceptance-config")
                .doesNotContain("git-auth", "broker-secret");
        assertThat(findByName(worker.path("volumeMounts"), "broker-socket")
                .path("readOnly").asBoolean()).isTrue();
        assertThat(brokerMounts)
                .containsExactly("broker-secret", "broker-socket", "broker-tmp")
                .doesNotContain("workspace", "acceptance-config", "git-auth", "codex-config");
        assertThat(findByName(broker.path("volumeMounts"), "broker-socket")
                .path("readOnly").asBoolean(true)).isFalse();
        assertThat(gitInitMounts).contains("git-auth").doesNotContain("broker-secret");
        assertThat(worker.path("env").toString())
                .contains("REACHAI_MANAGED_RUNTIME_BROKER_SOCKET", "/run/reachai/broker/runtime.sock",
                        "REACHAI_MANAGED_MODEL_RELAY_HOST", "model-relay.reachai-egress.svc",
                        "REACHAI_MANAGED_MODEL_RELAY_PORT", "443")
                .doesNotContain("REACHAI_MANAGED_WORKER_TOKEN_FILE", "broker-secret");
        assertThat(broker.path("env").toString())
                .contains("REACHAI_MANAGED_WORKER_TOKEN_FILE", "/run/reachai/broker-secret/token");
        JsonNode volumes = pod.path("volumes");
        JsonNode brokerSecretVolume = findByName(volumes, "broker-secret");
        JsonNode acceptanceVolume = findByName(volumes, "acceptance-config");
        assertThat(brokerSecretVolume.at("/secret/items/0/key").asText()).isEqualTo("token");
        assertThat(brokerSecretVolume.at("/secret/defaultMode").asInt()).isEqualTo(288);
        assertThat(acceptanceVolume.at("/secret/items/0/key").asText())
                .isEqualTo("acceptance-commands.json");
        assertThat(job.toString())
                .doesNotContain(request.workerBootstrapToken())
                .contains("automountServiceAccountToken", "network-policy-required");
    }

    @Test
    void cleanupDeletesOnlyTheDeterministicJobPolicyAndExecutionSecret() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ManagedKubernetesApiClient api = mock(ManagedKubernetesApiClient.class);
        when(api.exists(anyString())).thenReturn(true);
        KubernetesManagedSandboxProvisioner provisioner = new KubernetesManagedSandboxProvisioner(
                api, properties(), registry(objectMapper), objectMapper);
        ManagedSandboxProvisioner.SandboxHandle handle = provisioner.provision(request());

        provisioner.cleanup(new ManagedSandboxProvisioner.CleanupRequest(
                request().executionId(), handle.sandboxRef()));

        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(api, times(3)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).containsExactly(
                "/apis/batch/v1/namespaces/reachai-sandbox/jobs/" + handle.sandboxRef(),
                "/apis/networking.k8s.io/v1/namespaces/reachai-sandbox/networkpolicies/"
                        + handle.sandboxRef() + "-egress",
                "/api/v1/namespaces/reachai-sandbox/secrets/" + handle.sandboxRef() + "-worker");
    }

    @Test
    void refusesMutableWorkerImagesAndNonImmutableWorkspaceRevisions() {
        ObjectMapper objectMapper = new ObjectMapper();
        ManagedKubernetesSandboxProperties mutable = properties();
        mutable.setWorkerImage("registry.example/reachai/worker:latest");
        assertThatThrownBy(() -> new KubernetesManagedSandboxProvisioner(
                mock(ManagedKubernetesApiClient.class), mutable, registry(objectMapper), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("image digest");

        assertThatThrownBy(() -> new ManagedSandboxPolicyRegistry(
                objectMapper,
                "{\"PROJECT_A\":{\"type\":\"GIT\",\"repositoryUrl\":\"https://git.example/project.git\",\"revision\":\"main\"}}",
                "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable commit SHA");
    }

    @Test
    void refusesRuntimeOriginsWhosePathOrPortEscapesTheNetworkPolicy() {
        ObjectMapper objectMapper = new ObjectMapper();
        ManagedKubernetesSandboxProperties path = properties();
        path.setRuntimeBaseUrl("https://runtime.reachai.svc:18604/internal");
        assertThatThrownBy(() -> new KubernetesManagedSandboxProvisioner(
                mock(ManagedKubernetesApiClient.class), path, registry(objectMapper), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential-free HTTPS");

        ManagedKubernetesSandboxProperties port = properties();
        port.setRuntimeBaseUrl("https://runtime.reachai.svc");
        assertThatThrownBy(() -> new KubernetesManagedSandboxProvisioner(
                mock(ManagedKubernetesApiClient.class), port, registry(objectMapper), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtimeEgressPort");
    }

    @Test
    void refusesToCreateAJobUntilTheNamespaceWideDefaultDenyPolicyExists() {
        ObjectMapper objectMapper = new ObjectMapper();
        ManagedKubernetesApiClient api = mock(ManagedKubernetesApiClient.class);
        when(api.exists(anyString())).thenReturn(true);
        when(api.exists("/apis/networking.k8s.io/v1/namespaces/reachai-sandbox/networkpolicies/"
                + "default-deny-all")).thenReturn(false);
        KubernetesManagedSandboxProvisioner provisioner = new KubernetesManagedSandboxProvisioner(
                api, properties(), registry(objectMapper), objectMapper);

        assertThatThrownBy(() -> provisioner.provision(request()))
                .isInstanceOf(ManagedExecutionException.class)
                .hasMessageContaining("prerequisite");
        verify(api, never()).create(anyString(), org.mockito.ArgumentMatchers.any());
    }

    private ManagedKubernetesSandboxProperties properties() {
        ManagedKubernetesSandboxProperties properties = new ManagedKubernetesSandboxProperties();
        properties.setNamespace("reachai-sandbox");
        properties.setRuntimeBaseUrl("https://runtime.reachai.svc:18604");
        properties.setWorkerImage("registry.example/reachai/worker" + DIGEST);
        properties.setGitInitImage("registry.example/reachai/git-init" + DIGEST);
        properties.setCodexConfigMapName("reachai-codex-relay-config");
        properties.setRuntimeClassName("gvisor");
        properties.setWorkerServiceAccountName("reachai-managed-worker");
        properties.setNetworkPolicyEnabled(true);
        properties.setDnsProxyIp("10.96.0.53");
        properties.setRuntimeEgressNamespace("reachai");
        properties.setRuntimeEgressApp("reachai-runtime-service");
        properties.setRuntimeServiceHost("runtime.reachai.svc");
        properties.setModelRelayEgressNamespace("reachai-egress");
        properties.setModelRelayEgressApp("reachai-model-relay");
        properties.setModelRelayServiceHost("model-relay.reachai-egress.svc");
        properties.setGitProxyEgressNamespace("reachai-egress");
        properties.setGitProxyEgressApp("reachai-git-proxy");
        properties.setGitProxyServiceHost("git.example");
        properties.setDependencyProxyEgressNamespace("reachai-egress");
        properties.setDependencyProxyEgressApp("reachai-dependency-proxy");
        properties.setDependencyProxyServiceHost("dependency-proxy.reachai-egress.svc");
        return properties;
    }

    private ManagedSandboxPolicyRegistry registry(ObjectMapper objectMapper) {
        return new ManagedSandboxPolicyRegistry(
                objectMapper,
                "{\"PROJECT_A\":{\"type\":\"GIT\","
                        + "\"repositoryUrl\":\"https://git.example/project.git\","
                        + "\"revision\":\"" + "b".repeat(40) + "\","
                        + "\"credentialSecretName\":\"project-a-git-read\"}}",
                "{\"PROJECT_A:PROJECT_DEFAULT\":[{\"name\":\"unit\","
                        + "\"argv\":[\"mvn\",\"test\"],\"timeoutMs\":600000}]}" );
    }

    private ProvisioningRequest request() {
        return new ProvisioningRequest(
                "mex_kubernetes_test",
                "token-0123456789-abcdefghijklmnopqrstuvwxyz",
                "tenant-a",
                "PROJECT_A",
                "AI_CODING_TASK",
                "task-1",
                "CODEX",
                "WORKSPACE_PATCH",
                "PROJECT_DEFAULT",
                1_800);
    }

    private List<String> values(JsonNode array, String field) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode item : array) values.add(item.path(field).asText());
        return values;
    }

    private JsonNode findByName(JsonNode array, String name) {
        for (JsonNode item : array) {
            if (name.equals(item.path("name").asText())) return item;
        }
        throw new AssertionError("Missing item " + name);
    }
}
