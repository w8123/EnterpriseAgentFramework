package com.enterprise.ai.control.slot;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotManagementControllerTest {

    @Test
    void listsExtractorsAndRunsDictionaryTestLocally() {
        SlotDeptMapper deptMapper = mock(SlotDeptMapper.class);
        SlotUserMapper userMapper = mock(SlotUserMapper.class);
        SlotManagementController controller = controller(deptMapper, userMapper);
        SlotDeptEntity dept = dept(1L, "研发部", "yanfa", "技术部");
        SlotUserEntity user = user(2L, "张三", "zhangsan", "A001");
        when(deptMapper.selectList(any())).thenReturn(List.of(dept));
        when(userMapper.selectList(any())).thenReturn(List.of(user));

        ResponseEntity<List<SlotManagementController.SlotExtractorInfo>> extractors = controller.listExtractors();
        ResponseEntity<SlotManagementController.SlotExtractorTestResponse> tested =
                controller.testExtractor(new SlotManagementController.SlotExtractorTestRequest(
                        "请找张三确认研发部排期",
                        "owner",
                        "负责人",
                        "string",
                        null,
                        null,
                        null));

        assertEquals(HttpStatus.OK, extractors.getStatusCode());
        assertTrue(extractors.getBody().stream().anyMatch(item -> "DeptSlotExtractor".equals(item.name())));
        assertTrue(tested.getBody().results().stream().anyMatch(row -> row.hit() && "研发部".equals(row.value())));
        assertTrue(tested.getBody().results().stream().anyMatch(row -> row.hit() && "张三".equals(row.value())));
    }

    @Test
    void managesDeptAndUserDictionaries() {
        SlotDeptMapper deptMapper = mock(SlotDeptMapper.class);
        SlotUserMapper userMapper = mock(SlotUserMapper.class);
        SlotManagementController controller = controller(deptMapper, userMapper);
        SlotDeptEntity dept = dept(1L, "研发部", "yanfa", "技术部");
        SlotUserEntity user = user(2L, "张三", "zhangsan", "A001");
        when(deptMapper.selectCount(any())).thenReturn(1L);
        when(deptMapper.selectList(any())).thenReturn(List.of(dept));
        when(deptMapper.selectById(1L)).thenReturn(dept);
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(userMapper.selectById(2L)).thenReturn(user);

        assertEquals(1, controller.pageDept(1, 20, "研发").getBody().records().size());
        assertEquals("研发部", controller.listAllDept().getBody().get(0).name());
        assertEquals("研发部", controller.createDept(new SlotManagementController.SlotDeptRow(
                null, null, "研发部", "yanfa", "技术部", null, true, null, null)).getBody().name());
        assertEquals("研发部", controller.updateDept(1L, new SlotManagementController.SlotDeptRow(
                1L, null, "研发部", "yanfa", "技术部", null, true, null, null)).getBody().name());
        assertEquals(HttpStatus.OK, controller.deleteDept(1L).getStatusCode());
        assertEquals(1, controller.pageUser(1, 20, "张", null).getBody().records().size());
        assertEquals("张三", controller.createUser(new SlotManagementController.SlotUserRow(
                null, 1L, "张三", "zhangsan", "A001", null, true, null, null)).getBody().name());
        assertEquals("张三", controller.updateUser(2L, new SlotManagementController.SlotUserRow(
                2L, 1L, "张三", "zhangsan", "A001", null, true, null, null)).getBody().name());
        assertEquals(HttpStatus.OK, controller.deleteUser(2L).getStatusCode());

        verify(deptMapper).insert(any());
        verify(deptMapper).updateById(any());
        verify(deptMapper).deleteById(1L);
        verify(userMapper).insert(any());
        verify(userMapper).updateById(any());
        verify(userMapper).deleteById(2L);
    }

    @Test
    void listsLogsMetricsAndUpsertsFieldBindings() {
        SlotExtractLogMapper logMapper = mock(SlotExtractLogMapper.class);
        FieldExtractorBindingMapper bindingMapper = mock(FieldExtractorBindingMapper.class);
        SlotManagementController controller = controller(logMapper, bindingMapper);
        SlotExtractLogEntity log = new SlotExtractLogEntity();
        log.setId(9L);
        log.setExtractorName("DeptSlotExtractor");
        log.setSkillName("approval");
        log.setFieldKey("dept");
        log.setHit(true);
        log.setValue("研发部");
        log.setConfidence(0.91);
        log.setLatencyMs(12L);
        log.setCreateTime(LocalDateTime.of(2026, 6, 30, 20, 0));
        when(logMapper.selectCount(any())).thenReturn(1L);
        when(logMapper.selectList(any())).thenReturn(List.of(log));
        FieldExtractorBindingEntity binding = new FieldExtractorBindingEntity();
        binding.setId(3L);
        binding.setSkillName("approval");
        binding.setFieldKey("dept");
        binding.setExtractorNamesJson("[\"DeptSlotExtractor\"]");
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(bindingMapper.selectOne(any())).thenReturn(binding);

        assertEquals(1, controller.pageLogs(1, 20, "DeptSlotExtractor", "approval", true, 7).getBody().records().size());
        assertEquals("DeptSlotExtractor", controller.metrics(7).getBody().get(0).extractorName());
        assertEquals("approval", controller.listBindings("approval").getBody().get(0).skillName());
        assertTrue(controller.upsertBinding(new SlotManagementController.FieldExtractorBindingUpsertRequest(
                "approval",
                "dept",
                List.of("DeptSlotExtractor"))).getBody().ok());

        verify(bindingMapper).updateById(any());
    }

    private SlotManagementController controller(SlotDeptMapper deptMapper, SlotUserMapper userMapper) {
        return new SlotManagementController(
                deptMapper,
                userMapper,
                mock(SlotExtractLogMapper.class),
                mock(FieldExtractorBindingMapper.class));
    }

    private SlotManagementController controller(SlotExtractLogMapper logMapper, FieldExtractorBindingMapper bindingMapper) {
        return new SlotManagementController(
                mock(SlotDeptMapper.class),
                mock(SlotUserMapper.class),
                logMapper,
                bindingMapper);
    }

    private SlotDeptEntity dept(Long id, String name, String pinyin, String aliases) {
        SlotDeptEntity entity = new SlotDeptEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setPinyin(pinyin);
        entity.setAliases(aliases);
        entity.setEnabled(true);
        return entity;
    }

    private SlotUserEntity user(Long id, String name, String pinyin, String employeeNo) {
        SlotUserEntity entity = new SlotUserEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setPinyin(pinyin);
        entity.setEmployeeNo(employeeNo);
        entity.setEnabled(true);
        return entity;
    }
}
