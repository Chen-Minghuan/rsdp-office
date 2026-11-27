package com.rsdp.officeproduct.importer.office.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OfficeRoomSchemeMapper {
    record Row(String activeSlot, String inactiveJson, String confirmedSlot) {}

    @Select("SELECT active_slot, inactive_json::text AS inactive_json, confirmed_slot FROM office_room_scheme WHERE floor_id=#{floorId} AND zone_id=#{zoneId}")
    Row find(@Param("floorId") String floorId, @Param("zoneId") String zoneId);

    @Insert("""
        INSERT INTO office_room_scheme(floor_id, zone_id, active_slot, inactive_json)
        VALUES (#{floorId}, #{zoneId}, #{activeSlot}, CAST(#{inactiveJson} AS jsonb))
        ON CONFLICT (floor_id, zone_id) DO UPDATE
          SET active_slot=EXCLUDED.active_slot, inactive_json=EXCLUDED.inactive_json
        """)
    void save(@Param("floorId") String floorId, @Param("zoneId") String zoneId,
              @Param("activeSlot") String activeSlot, @Param("inactiveJson") String inactiveJson);

    /** confirmedSlot 传 null 表示取消方案选择确认。 */
    @Update("UPDATE office_room_scheme SET confirmed_slot=#{confirmedSlot,jdbcType=VARCHAR} WHERE floor_id=#{floorId} AND zone_id=#{zoneId}")
    int setConfirmedSlot(@Param("floorId") String floorId, @Param("zoneId") String zoneId,
                         @Param("confirmedSlot") String confirmedSlot);

    /** 已创建 Plan B（存在方案行）但尚未确认使用哪个方案的空间。 */
    @Select("SELECT zone_id FROM office_room_scheme WHERE floor_id=#{floorId} AND confirmed_slot IS NULL")
    List<String> findUnconfirmedZoneIds(@Param("floorId") String floorId);
}
