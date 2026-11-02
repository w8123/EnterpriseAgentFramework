package com.enterprise.ai.control.memory;

import java.util.List;

/** The business systems a project memory layer can be opened for, supplied by the module that knows them. */
public interface PersonalMemoryProjectCatalog {

    List<ProjectOption> projects();

    record ProjectOption(String projectCode, String name) {
    }
}
