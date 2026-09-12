package com.enterprise.ai.runtime.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** The owner and attempt key identify a creation; the immutable payload detects conflicting reuse. */
record RuntimeDebugCreationIdentity(String sessionId, String requestHash) {
    static RuntimeDebugCreationIdentity from(RuntimeDebugSessionOwner owner,
            RuntimeExecutableDebugSessionService.CreateRequest request, ObjectMapper json) {
        String key=request.idempotencyKey();
        if(key==null)return null; // Existing callers intentionally start a new session on each request.
        try {
            String id=sessionId(owner,key,json);
            ObjectNode payload=json.valueToTree(request);
            payload.remove("idempotencyKey");
            return new RuntimeDebugCreationIdentity(id,hash(json.writeValueAsString(canonical(payload,json))));
        }catch(IllegalArgumentException failure){throw failure;}
        catch(Exception failure){throw new IllegalArgumentException("debug creation request cannot be fingerprinted",failure);}
    }

    static String sessionId(RuntimeDebugSessionOwner owner,String key,ObjectMapper json) {
        if(key==null || !key.trim().matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("invalid debug creation idempotencyKey");
        try {return hash(json.writeValueAsString(List.of("debug-creation-v1",owner.tenantId(),owner.userId(),key.trim())));}
        catch(Exception failure){throw new IllegalArgumentException("debug creation identity cannot be encoded",failure);}
    }

    private static JsonNode canonical(JsonNode node,ObjectMapper json){
        if(node.isObject()){
            ObjectNode result=json.createObjectNode();var names=new ArrayList<String>();node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);for(String name:names)result.set(name,canonical(node.get(name),json));return result;
        }
        if(node.isArray()){
            ArrayNode result=json.createArrayNode();for(JsonNode item:node)result.add(canonical(item,json));return result;
        }
        return node;
    }
    private static String hash(String value)throws Exception{
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
