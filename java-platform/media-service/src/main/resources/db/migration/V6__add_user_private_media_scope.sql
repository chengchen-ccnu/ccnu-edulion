-- T14.2.4 Media V6：普通学习账号上传的 AI 题图使用 USER_PRIVATE，不再伪装为教师资产。
-- 既有 SYSTEM/TEACHER 行保持不变；读取和绑定仍按 owner、用途与 READY 状态校验。
ALTER TABLE media_asset DROP CONSTRAINT chk_media_asset_scope;
ALTER TABLE media_asset
    ADD CONSTRAINT chk_media_asset_scope CHECK (scope IN ('SYSTEM', 'TEACHER', 'USER_PRIVATE'));
