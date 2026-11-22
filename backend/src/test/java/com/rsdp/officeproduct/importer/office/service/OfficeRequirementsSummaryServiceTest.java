package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RequirementsSummaryProperties;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.service.RequirementsSummaryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OfficeRequirementsSummaryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private OfficeDesignProjectMapper projects;
    private OfficeProjectAccessService access;
    private RequirementsSummaryClient client;
    private OfficeRequirementsSummaryService service;
    private OfficeDesignProject project;

    @BeforeEach
    void setup() {
        projects = mock(OfficeDesignProjectMapper.class);
        access = mock(OfficeProjectAccessService.class);
        client = mock(RequirementsSummaryClient.class);
        RequirementsSummaryProperties properties = new RequirementsSummaryProperties();
        properties.setModel("qwen-turbo");
        service = new OfficeRequirementsSummaryService(projects, access, client, properties, mapper);
        project = new OfficeDesignProject();
        project.setProjectId("OPRJ-1");
        project.setCustomerRequirements("开放办公区60人，不要开放式会议室。");
        project.setRequirementsRevision(1);
        when(projects.selectById("OPRJ-1")).thenReturn(project);
    }

    @Test
    void returnsPersistedSummaryForSameRevisionWithoutCallingModel() {
        project.setRequirementsSummary("{\"keyPoints\":[\"开放办公区计划容纳60人。\"]}");
        project.setRequirementsSummaryRevision(1);
        project.setRequirementsSummaryModel("qwen-turbo");
        project.setRequirementsSummaryPromptVersion("requirements-facts-v1");

        var result = service.ensureSummary("OPRJ-1", 1);

        assertTrue(result.isCacheHit());
        assertEquals(List.of("开放办公区计划容纳60人。"), result.getKeyPoints());
        verifyNoInteractions(client);
        verify(access).requireMember("OPRJ-1");
    }

    @Test
    void generatesAndPersistsStructuredSummary() {
        when(client.summarize(project.getCustomerRequirements())).thenReturn(List.of("开放办公区计划容纳60人。"));
        when(projects.saveRequirementsSummary(anyString(), anyInt(), anyString(), anyString(), anyString(), any())).thenReturn(1);

        var result = service.ensureSummary("OPRJ-1", 1);

        assertFalse(result.isCacheHit());
        assertEquals(1, result.getSourceRevision());
        assertEquals(List.of("开放办公区计划容纳60人。"), result.getKeyPoints());
        verify(projects).saveRequirementsSummary(anyString(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectsStaleRevisionBeforeCallingModel() {
        project.setRequirementsRevision(2);

        BusinessException error = assertThrows(BusinessException.class, () -> service.ensureSummary("OPRJ-1", 1));

        assertTrue(error.getMessage().contains("已修改"));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsModelResultWhenRevisionChangesBeforeSave() {
        when(client.summarize(anyString())).thenAnswer(call -> {
            project.setRequirementsRevision(2);
            return List.of("旧版本总结。");
        });
        when(projects.saveRequirementsSummary(anyString(), anyInt(), anyString(), anyString(), anyString(), any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.ensureSummary("OPRJ-1", 1));
    }

    @Test
    void rejectsBlankSourceWithoutCallingModel() {
        project.setCustomerRequirements("  ");

        assertThrows(BusinessException.class, () -> service.ensureSummary("OPRJ-1", 1));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsOverlongSourceWithoutCallingModel() {
        project.setCustomerRequirements("需".repeat(4001));

        BusinessException error = assertThrows(BusinessException.class, () -> service.ensureSummary("OPRJ-1", 1));

        assertTrue(error.getMessage().contains("4000"));
        verifyNoInteractions(client);
    }

    @Test
    void coalescesConcurrentRequestsForSameRevision() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        when(client.summarize(anyString())).thenAnswer(call -> {
            modelStarted.countDown();
            assertTrue(releaseModel.await(5, TimeUnit.SECONDS));
            return List.of("开放办公区计划容纳60人。");
        });
        when(projects.saveRequirementsSummary(anyString(), anyInt(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            project.setRequirementsSummary("{\"keyPoints\":[\"开放办公区计划容纳60人。\"]}");
            project.setRequirementsSummaryRevision(1);
            project.setRequirementsSummaryModel("qwen-turbo");
            project.setRequirementsSummaryPromptVersion("requirements-facts-v1");
            return 1;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.ensureSummary("OPRJ-1", 1));
            assertTrue(modelStarted.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> service.ensureSummary("OPRJ-1", 1));
            releaseModel.countDown();
            assertEquals(List.of("开放办公区计划容纳60人。"), first.get(5, TimeUnit.SECONDS).getKeyPoints());
            assertEquals(List.of("开放办公区计划容纳60人。"), second.get(5, TimeUnit.SECONDS).getKeyPoints());
        } finally {
            pool.shutdownNow();
        }
        verify(client, times(1)).summarize(anyString());
    }
}
