package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rspu_scene")
public class RspuScene {

    @TableId
    private String rspuId;

    private String dictType;

    @TableField("scene_code")
    private String sceneCode;

    private LocalDateTime createdAt;
}
