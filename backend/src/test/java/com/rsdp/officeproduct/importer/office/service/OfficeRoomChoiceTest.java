package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.CanvasDto;
import com.rsdp.officeproduct.importer.office.dto.CartItemDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeSchemeDtos;
import com.rsdp.officeproduct.importer.office.mapper.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfficeRoomChoiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OfficeService office = mock(OfficeService.class);
    private final OfficeProjectAccessService access = mock(OfficeProjectAccessService.class);
    private final OfficeWorkspaceLockMapper locks = mock(OfficeWorkspaceLockMapper.class);
    private final OfficeRoomSchemeMapper schemes = mock(OfficeRoomSchemeMapper.class);
    private final OfficeCanvasMapper canvasRecords = mock(OfficeCanvasMapper.class);
    private final OfficeProposalConfirmationMapper confirmations = mock(OfficeProposalConfirmationMapper.class);

    private void savedCanvas() throws Exception {
        var canvas = new CanvasDto();
        canvas.setProjectId("FLOOR");
        canvas.setRevision(4);
        canvas.setSchemeToken("TOKEN-B");
        canvas.setObjects(json.readValue("""
            [{"id":"Z1","kind":"zone","name":"保留房间","reviewUnit":"mm",
              "polygon":[[0,0],[6000,0],[6000,4000],[0,4000]],"reviewConfirmation":{"status":"CONFIRMED"}},
             {"id":"F1","kind":"furniture","zoneId":"Z1","x":1000,"y":1000,"w":1200,"h":600,
              "rotation":0,"layoutConfirmed":true,"productId":"SKU",
              "productSelection":{"product":{"id":"SKU","rspuId":"RSPU","price":100}}}]
            """, new TypeReference<List<Object>>() {}));
        var line = new CartItemDto();
        line.setLineId("LINE");
        line.setZoneId("Z1");
        line.setProductId("SKU");
        line.setQty(1);
        when(office.getCanvas("FLOOR")).thenReturn(canvas);
        when(office.listCart("FLOOR")).thenReturn(List.of(line));
    }

    private OfficeProposalConfirmationService proposalService() {
        return new OfficeProposalConfirmationService(office, access, locks, confirmations, schemes, json);
    }

    private OfficeRoomSchemeService roomService() {
        return new OfficeRoomSchemeService(office, locks, schemes, mock(OfficeCartMapper.class), json, canvasRecords);
    }

    @Test void deletedRoomsDoNotBlockButExistingUnconfirmedRoomDoes() throws Exception {
        savedCanvas();
        var service = proposalService();
        when(schemes.findUnconfirmedZoneIds("FLOOR")).thenReturn(List.of("DELETED-ZONE"));
        assertNotNull(service.confirm("FLOOR", new OfficeProposalConfirmationService.Request(4, "TOKEN-B")));
        when(schemes.findUnconfirmedZoneIds("FLOOR")).thenReturn(List.of("Z1"));
        var error = assertThrows(BusinessException.class,
            () -> service.confirm("FLOOR", new OfficeProposalConfirmationService.Request(4, "TOKEN-B")));
        assertTrue(error.getMessage().contains("保留房间"));
    }

    @Test void staleChoiceRejectedAndMatchingChoiceLocksExpectedSlot() throws Exception {
        savedCanvas();
        when(schemes.find("FLOOR", "Z1")).thenReturn(new OfficeRoomSchemeMapper.Row("B", "{}", null));
        var service = roomService();
        assertThrows(BusinessException.class,
            () -> service.confirmChoice("FLOOR", new OfficeSchemeDtos.Change("Z1", 3, "TOKEN-A", "A")));
        assertThrows(BusinessException.class,
            () -> service.confirmChoice("FLOOR", new OfficeSchemeDtos.Change("Z1", 4, "TOKEN-A", "B")));
        assertThrows(BusinessException.class,
            () -> service.confirmChoice("FLOOR", new OfficeSchemeDtos.Change("Z1", 4, "TOKEN-B", "A")));
        verify(schemes, never()).setConfirmedSlot(anyString(), anyString(), any());
        var result = service.confirmChoice("FLOOR", new OfficeSchemeDtos.Change("Z1", 4, "TOKEN-B", "B"));
        assertEquals("B", result.confirmedSlot());
        verify(canvasRecords).retireProposalConfirmations("FLOOR");
    }

    @Test void cancelChoiceInvalidatesFloorConfirmation() throws Exception {
        savedCanvas();
        when(schemes.find("FLOOR", "Z1")).thenReturn(new OfficeRoomSchemeMapper.Row("B", "{}", "B"));
        assertNull(roomService().cancelChoice("FLOOR", new OfficeSchemeDtos.Change("Z1", 4, "TOKEN-B", "B")).confirmedSlot());
        verify(canvasRecords).retireProposalConfirmations("FLOOR");
    }

    @Test void unconfirmedChoiceInvalidatesLatestEvenWhenCanvasIsUnchanged() throws Exception {
        savedCanvas();
        var service = proposalService();
        when(schemes.findUnconfirmedZoneIds("FLOOR")).thenReturn(List.of());
        service.confirm("FLOOR", new OfficeProposalConfirmationService.Request(4, "TOKEN-B"));
        var row = ArgumentCaptor.forClass(OfficeProposalConfirmationMapper.Row.class);
        verify(confirmations).insert(row.capture());
        when(confirmations.latest("FLOOR")).thenReturn(row.getValue());
        assertTrue(service.latest("FLOOR").current());
        when(schemes.findUnconfirmedZoneIds("FLOOR")).thenReturn(List.of("Z1"));
        assertFalse(service.latest("FLOOR").current());
    }
}
