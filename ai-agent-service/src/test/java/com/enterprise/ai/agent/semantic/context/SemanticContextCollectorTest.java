package com.enterprise.ai.agent.semantic.context;

import com.enterprise.ai.agent.scan.ScanModuleEntity;
import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticContextCollectorTest {

    @TempDir
    Path tmp;

    @Test
    void projectLevelCollectsReadmeAndPomDescription() throws Exception {
        Files.writeString(tmp.resolve("README.md"), "# Legacy CRM\n\n客户管理系统。", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("pom.xml"),
                "<project><description>传统 CRM 系统</description></project>",
                StandardCharsets.UTF_8);

        ScanModuleService moduleService = mock(ScanModuleService.class);
        SemanticContextCollector collector = new SemanticContextCollector(moduleService);
        ScanProjectEntity project = project();
        ScanModuleEntity module = new ScanModuleEntity();
        module.setName("CustomerController");
        module.setDisplayName("客户管理");

        SemanticContext ctx = collector.collectForProject(project, List.of(module));

        assertAll(
                () -> assertEquals("project", ctx.level()),
                () -> assertEquals("legacy-crm", ctx.projectName()),
                () -> assertEquals("传统 CRM 系统", ctx.projectDescription()),
                () -> assertTrue(ctx.readmeExcerpt().contains("Legacy CRM")),
                () -> assertEquals(List.of("客户管理"), ctx.moduleIndex())
        );
    }

    @Test
    void moduleLevelFindsControllerAndDependencySnippets() throws Exception {
        Path javaDir = tmp.resolve("src/main/java/com/demo");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("OrderController.java"), """
                package com.demo;
                public class OrderController {
                    private final OrderService orderService;
                    public OrderController(OrderService orderService) { this.orderService = orderService; }
                    public String listOrders() { return orderService.listAll(); }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(javaDir.resolve("OrderService.java"), """
                package com.demo;
                public class OrderService {
                    public String listAll() { return "[]"; }
                }
                """, StandardCharsets.UTF_8);

        ScanModuleService moduleService = mock(ScanModuleService.class);
        when(moduleService.parseClasses("[\"OrderController\"]")).thenReturn(List.of("OrderController"));

        SemanticContextCollector collector = new SemanticContextCollector(moduleService);
        ScanProjectEntity project = project();
        ScanModuleEntity module = new ScanModuleEntity();
        module.setName("OrderController");
        module.setDisplayName("订单模块");
        module.setSourceClasses("[\"OrderController\"]");

        SemanticContext ctx = collector.collectForModule(project, module, List.of());

        assertEquals("module", ctx.level());
        assertNotNull(ctx.controllerSources());
        assertEquals(1, ctx.controllerSources().size());
        assertTrue(ctx.controllerSources().get(0).content().contains("OrderController"));
        assertTrue(ctx.serviceSnippets().stream().anyMatch(s -> "OrderService".equals(s.qualifier())));
    }

    @Test
    void toolLevelExtractsMethodBodyAndDtoReferences() throws Exception {
        Path javaDir = tmp.resolve("src");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("UserController.java"), """
                package com.demo;
                public class UserController {
                    public UserVO getUser(Long id) {
                        return null;
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(javaDir.resolve("UserVO.java"), """
                package com.demo;
                public class UserVO {
                    public Long id;
                    public String name;
                }
                """, StandardCharsets.UTF_8);

        ScanModuleService moduleService = mock(ScanModuleService.class);
        SemanticContextCollector collector = new SemanticContextCollector(moduleService);
        ScanProjectEntity project = project();
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setName("demo__get_user");
        tool.setHttpMethod("GET");
        tool.setContextPath("/api");
        tool.setEndpointPath("/user/{id}");
        tool.setSourceLocation("UserController.java#UserController#getUser");
        tool.setResponseType("UserVO");

        SemanticContext ctx = collector.collectForTool(project, tool, null);

        assertEquals("tool", ctx.level());
        assertTrue(ctx.toolMethodSource().contains("getUser"));
        assertTrue(ctx.dtoSnippets().stream().anyMatch(s -> "UserVO".equals(s.qualifier())));
        assertEquals("GET /api/user/{id}", ctx.toolEndpoint());
    }

    @Test
    void toolLevelHandlesMissingControllerGracefully() {
        ScanModuleService moduleService = mock(ScanModuleService.class);
        SemanticContextCollector collector = new SemanticContextCollector(moduleService);
        ScanProjectEntity project = project();
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setName("demo__missing");
        tool.setHttpMethod("POST");
        tool.setEndpointPath("/missing");
        tool.setSourceLocation("Missing.java#Missing#call");

        SemanticContext ctx = collector.collectForTool(project, tool, null);

        assertEquals("", ctx.toolMethodSource());
        assertFalse(ctx.toolEndpoint().isBlank());
    }

    private ScanProjectEntity project() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(1L);
        project.setName("legacy-crm");
        project.setScanPath(tmp.toString());
        project.setBaseUrl("http://localhost:9001");
        return project;
    }
}
