package com.rsdp.officeproduct.importer.office.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface OfficeRoomSchemeMapper {
    record Row(String activeSlot, String inactiveJson) {}

    @Select("SELECT active_slot, inactive_json::text AS inactive_json FROM office_room_scheme WHERE floor_id=#{floorId} AND zone_id=#{zoneId}")
    Row find(@Param("floorId") String floorId, @Param("zoneId") String zoneId);

    @Insert("""
        INSERT INTO office_room_scheme(floor_id, zone_id, active_slot, inactive_json)
        VALUES (#{floorId}, #{zoneId}, #{activeSlot}, CAST(#{inactiveJson} AS jsonb))
        ON CONFLICT (floor_id, zone_id) DO UPDATE
          SET active_slot=EXCLUDED.active_slot, inactive_json=EXCLUDED.inactive_json
        """)
    void save(@Param("floorId") String floorId, @Param("zoneId") String zoneId,
              @Param("activeSlot") String activeSlot, @Param("inactiveJson") String inactiveJson);
}
