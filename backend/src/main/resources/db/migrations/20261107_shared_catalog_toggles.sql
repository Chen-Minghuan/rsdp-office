-- SHARED-001：公共库（主项目全库商品）两级开关
-- 工作区级（经销商主号控制，全员受约束）+ 用户级（成员自选不看，仅工作区开启时生效）。
-- 两级均按请求实时查库，切换即时生效。

ALTER TABLE office_workspace
    ADD COLUMN IF NOT EXISTS shared_catalog_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE office_user
    ADD COLUMN IF NOT EXISTS hide_shared_catalog BOOLEAN NOT NULL DEFAULT FALSE;
