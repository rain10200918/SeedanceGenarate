-- MiniMax Music 3 音乐生成定价配置：按次固定 50 算力点（0.50 元 / 次）
INSERT INTO price_config (provider, model, billing_type, unit_price, currency, enabled, remark)
VALUES ('comfyui', 'minimax-music3', 'FLAT', 0.5000, 'CNY', 1, 'MiniMax Music 3 音乐生成（50算力点/次）')
ON DUPLICATE KEY UPDATE billing_type = 'FLAT', unit_price = 0.5000, enabled = 1;
