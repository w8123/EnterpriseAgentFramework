package com.enterprise.ai.runtime.managed;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "reachai.runtime.managed-executor.kubernetes")
public class ManagedKubernetesSandboxProperties {

    private String apiServer = "https://kubernetes.default.svc";
    private String namespace = "reachai-managed-executor";
    private String serviceAccountTokenFile = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private String clusterCaFile = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";

    private String runtimeBaseUrl;
    private String workerImage;
    private String gitInitImage;
    private String codexConfigMapName;
    private String runtimeClassName;
    private String imagePullPolicy = "IfNotPresent";
    private String allowedRuntimeClasses = "gvisor,kata-qemu,kata-fc";
    private String workerServiceAccountName;

    /** Per-execution deny-all NetworkPolicy is mandatory for the Kubernetes backend. */
    private boolean networkPolicyEnabled = false;
    /** Namespace-wide policy installed before any execution Job can be admitted. */
    private String staticDefaultDenyPolicyName = "default-deny-all";
    /** Restricted DNS proxy that resolves only the explicitly configured service hosts. */
    private String dnsProxyIp;
    private int dnsProxyPort = 53;

    private String runtimeEgressNamespace;
    private String runtimeEgressApp;
    private int runtimeEgressPort = 18604;
    private String runtimeServiceHost;

    private String modelRelayEgressNamespace;
    private String modelRelayEgressApp;
    private int modelRelayEgressPort = 443;
    private String modelRelayServiceHost;

    private String gitProxyEgressNamespace;
    private String gitProxyEgressApp;
    private int gitProxyEgressPort = 443;
    private String gitProxyServiceHost;

    private String dependencyProxyEgressNamespace;
    private String dependencyProxyEgressApp;
    private int dependencyProxyEgressPort = 443;
    private String dependencyProxyServiceHost;

    private int ttlSecondsAfterFinished = 600;
    private int terminationGracePeriodSeconds = 30;
    private String cpuRequest = "500m";
    private String cpuLimit = "2";
    private String memoryRequest = "1Gi";
    private String memoryLimit = "4Gi";
    private String ephemeralStorageRequest = "1Gi";
    private String ephemeralStorageLimit = "10Gi";
    private String workspaceSizeLimit = "10Gi";

    private String brokerCpuRequest = "25m";
    private String brokerCpuLimit = "250m";
    private String brokerMemoryRequest = "64Mi";
    private String brokerMemoryLimit = "256Mi";
    private String brokerEphemeralStorageRequest = "64Mi";
    private String brokerEphemeralStorageLimit = "256Mi";
}
