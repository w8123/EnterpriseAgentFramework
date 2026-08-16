package com.enterprise.ai.control.client.model;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "reachai-model-catalog", url = "${services.model-service.url:http://localhost:18601}")
public interface ControlModelCatalogClient {

    @RequestMapping(method = RequestMethod.GET, path = "/model/instances")
    ResponseEntity<Map<String, Object>> list(@RequestParam(value = "projectCode", required = false) String projectCode,
                                             @RequestParam(value = "modelType", required = false) String modelType,
                                             @RequestParam(value = "provider", required = false) String provider);

    @RequestMapping(method = RequestMethod.GET, path = "/model/instances/{id}")
    ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.GET, path = "/internal/model/instances/{id}")
    ResponseEntity<Map<String, Object>> getInternal(@PathVariable("id") String id);
}
