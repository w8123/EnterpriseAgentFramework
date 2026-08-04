package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.FindingReport;
import com.enterprise.ai.control.pageworkbench.persistence.PageAnalysisFindingMapper;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PageAnalysisApplicationServiceTest {

    @Test
    void rejectsMoreThanThreeFindingsBeforeMutatingTheCatalog() {
        PageAnalysisFindingMapper findingMapper = mock(PageAnalysisFindingMapper.class);
        ProjectPageMapper pageMapper = mock(ProjectPageMapper.class);
        PageAnalysisApplicationService service = new PageAnalysisApplicationService(
                findingMapper,
                pageMapper,
                new ObjectMapper());
        FindingReport report = new FindingReport(
                "f-1",
                null,
                "Finding",
                "Confirmed fact",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null);

        assertThrows(IllegalArgumentException.class, () -> service.applyAnalysis(
                7L,
                "orders",
                "pwt_analysis",
                "orders.list",
                Collections.nCopies(4, report)));

        verifyNoInteractions(pageMapper, findingMapper);
    }
}
