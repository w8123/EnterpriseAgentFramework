package com.enterprise.ai.runtime.execution.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Injectable DNS resolver so Workflow HTTP egress validation and connection pinning
 * share exactly one resolution result per hop.
 */
@FunctionalInterface
public interface WorkflowDnsResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
