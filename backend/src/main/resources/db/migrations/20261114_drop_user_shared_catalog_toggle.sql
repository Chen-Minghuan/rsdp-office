-- SHARED-004：公共库开关收口为工作区级单开关（仅经销商主号可切换，成员无个人开关）
-- 用户级"自选不看"偏好取消，列随之下线；历史值不再参与任何判定。

ALTER TABLE office_user
    DROP COLUMN IF EXISTS hide_shared_catalog;
