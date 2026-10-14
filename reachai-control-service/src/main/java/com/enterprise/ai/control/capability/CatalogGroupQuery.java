package com.enterprise.ai.control.capability;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class CatalogGroupQuery {
    private CatalogGroupQuery() { }

    static Long parse(HttpServletRequest request, Long projectId) {
        String value = request.getParameter("groupId");
        if (value == null) return null;
        try {
            if (request.getParameterValues("groupId").length != 1 || projectId == null || projectId <= 0 || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择当前项目的业务分类");
        }
    }

    static boolean ungrouped(HttpServletRequest request, Long projectId) {
        String value = request.getParameter("ungrouped");
        if (value == null) return false;
        if (request.getParameterValues("ungrouped").length != 1 || !("true".equals(value) || "false".equals(value))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未分类筛选条件无效");
        }
        if ("false".equals(value)) return false;
        if (projectId == null || projectId <= 0 || request.getParameter("groupId") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未分类筛选必须指定当前项目且不能同时选择分类");
        }
        return true;
    }
}
