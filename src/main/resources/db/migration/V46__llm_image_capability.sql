-- NULL preserves only legacy declarations; new admin-created channels explicitly write FALSE.
ALTER TABLE llm_channel ADD COLUMN supports_images BOOLEAN NULL DEFAULT NULL;
