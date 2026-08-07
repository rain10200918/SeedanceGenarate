CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    total_cost DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    register_ip VARCHAR(64),
    register_ip_location VARCHAR(128),
    last_login_ip VARCHAR(64),
    last_login_ip_location VARCHAR(128),
    last_login_time DATETIME,
    last_active_ip VARCHAR(64),
    last_active_ip_location VARCHAR(128),
    last_operation VARCHAR(128),
    last_operation_time DATETIME,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS invite_code (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'UNUSED',
    created_by BIGINT NOT NULL,
    used_by BIGINT,
    used_time DATETIME,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_invite_code_code (code),
    INDEX idx_invite_code_status (status),
    INDEX idx_invite_code_created_by (created_by)
);

CREATE TABLE IF NOT EXISTS user_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(64) NOT NULL UNIQUE,
    expire_time DATETIME NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_token_token (token),
    INDEX idx_user_token_user_id (user_id)
);

CREATE TABLE IF NOT EXISTS cost_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    seedance_task_id VARCHAR(128),
    duration INT,
    unit_price DECIMAL(12, 4) NOT NULL DEFAULT 0.0000,
    amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    biz_type VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'seedance',
    remark VARCHAR(255),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_cost_record_user_id (user_id),
    INDEX idx_cost_record_task_id (task_id)
);

CREATE TABLE IF NOT EXISTS video_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    task_id VARCHAR(128),
    prompt TEXT,
    images TEXT,
    duration INT,
    ratio VARCHAR(32),
    status VARCHAR(32),
    video_url VARCHAR(512),
    error_msg TEXT,
    cost_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    provider VARCHAR(32) NOT NULL DEFAULT 'seedance',
    node_id VARCHAR(64),
    model VARCHAR(64),
    output_type VARCHAR(16) NOT NULL DEFAULT 'VIDEO',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_video_task_user_id (user_id),
    INDEX idx_video_task_task_id (task_id),
    INDEX idx_video_task_status (status),
    INDEX idx_video_task_provider (provider)
);

CREATE TABLE IF NOT EXISTS model_access (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model VARCHAR(64) NOT NULL UNIQUE,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_model_access_model (model)
);

ALTER TABLE app_user ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER' AFTER password;
ALTER TABLE app_user ADD COLUMN register_ip VARCHAR(64) NULL AFTER total_cost;
ALTER TABLE app_user ADD COLUMN register_ip_location VARCHAR(128) NULL AFTER register_ip;
ALTER TABLE app_user ADD COLUMN last_login_ip VARCHAR(64) NULL AFTER register_ip_location;
ALTER TABLE app_user ADD COLUMN last_login_ip_location VARCHAR(128) NULL AFTER last_login_ip;
ALTER TABLE app_user ADD COLUMN last_login_time DATETIME NULL AFTER last_login_ip_location;
ALTER TABLE app_user ADD COLUMN last_active_ip VARCHAR(64) NULL AFTER last_login_time;
ALTER TABLE app_user ADD COLUMN last_active_ip_location VARCHAR(128) NULL AFTER last_active_ip;
ALTER TABLE app_user ADD COLUMN last_operation VARCHAR(128) NULL AFTER last_active_ip_location;
ALTER TABLE app_user ADD COLUMN last_operation_time DATETIME NULL AFTER last_operation;
ALTER TABLE video_task ADD COLUMN user_id BIGINT NULL AFTER id;
ALTER TABLE video_task ADD COLUMN cost_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00 AFTER error_msg;
ALTER TABLE video_task ADD INDEX idx_video_task_user_id (user_id);
ALTER TABLE video_task ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'seedance' AFTER cost_amount;
ALTER TABLE video_task ADD COLUMN node_id VARCHAR(64) NULL AFTER provider;
ALTER TABLE video_task ADD COLUMN model VARCHAR(64) NULL AFTER node_id;
ALTER TABLE video_task ADD COLUMN output_type VARCHAR(16) NOT NULL DEFAULT 'VIDEO' AFTER model;
ALTER TABLE video_task ADD INDEX idx_video_task_provider (provider);
ALTER TABLE cost_record ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'seedance' AFTER biz_type;

-- ============ 对外 API 服务（见 API_SERVICE_DESIGN.md） ============

CREATE TABLE IF NOT EXISTS api_key (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '属主用户',
  name VARCHAR(64) COMMENT '用途备注',
  key_prefix VARCHAR(16) NOT NULL COMMENT 'sk- 前 8 位，后台展示用',
  key_hash CHAR(64) NOT NULL UNIQUE COMMENT 'SHA-256 十六进制，不存明文',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED',
  expires_at DATETIME NULL,
  callback_url VARCHAR(512) COMMENT 'webhook 回调地址',
  webhook_secret VARCHAR(128) COMMENT '回调 HMAC 签名密钥',
  last_used_at DATETIME NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_api_key_user (user_id)
);

CREATE TABLE IF NOT EXISTS api_call_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL UNIQUE COMMENT '幂等键 / 追踪号',
  api_key_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL COMMENT '冗余，按人查',
  task_id VARCHAR(128) NULL COMMENT '关联 video_task.task_id；被拒请求为空',
  endpoint VARCHAR(64) NOT NULL,
  method VARCHAR(8) NOT NULL,
  model VARCHAR(64) NULL COMMENT '请求的模型标识（被拒也有值 → 统计维度）',
  provider VARCHAR(32) NULL,
  image_count INT NULL,
  duration INT NULL,
  ratio VARCHAR(32) NULL,
  megapixels DOUBLE NULL COMMENT '参数摘要；完整参数在 video_task，不重复存',
  status VARCHAR(16) NOT NULL COMMENT 'RECEIVED / SUCCESS / FAILED / REJECTED',
  http_code INT NULL COMMENT '被拒时记录 400/401/403/429',
  error_code VARCHAR(32) NULL,
  error_msg VARCHAR(512) NULL,
  client_ip VARCHAR(64) NULL,
  user_agent VARCHAR(255) NULL,
  cost_amount DECIMAL(12, 2) NULL COMMENT '从 cost_record 冗余，对账免 join',
  queued_ms BIGINT NULL COMMENT '提交 → 引擎接收',
  generate_ms BIGINT NULL COMMENT '引擎接收 → 终态',
  total_ms BIGINT NULL COMMENT '提交 → 终态',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_call_log_key (api_key_id, create_time),
  INDEX idx_call_log_model (model),
  INDEX idx_call_log_task (task_id)
);

CREATE TABLE IF NOT EXISTS webhook_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id VARCHAR(128) NOT NULL,
  api_key_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT '推送的任务终态（SUCCESS / FAILED）',
  payload TEXT,
  http_code INT NULL COMMENT '对方响应码',
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NULL,
  delivered TINYINT(1) NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_webhook_task_status (task_id, status) COMMENT '同任务同状态只投递一次（幂等）'
);

ALTER TABLE video_task ADD COLUMN api_key_id BIGINT NULL COMMENT '对外 API 来源判别列；空=UI' AFTER node_id;

-- 模型计价配置（管理端在线改价；未配置的模型沿用 yaml 默认价）
CREATE TABLE IF NOT EXISTS price_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider VARCHAR(32) NOT NULL COMMENT '提供方：seedance / comfyui',
  model VARCHAR(64) NOT NULL DEFAULT '' COMMENT '模型 id；空串 = 提供方默认价',
  billing_type VARCHAR(16) NOT NULL COMMENT 'PER_SECOND 按秒 / FLAT 按次固定',
  unit_price DECIMAL(10, 4) NOT NULL COMMENT '单价（元）',
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  remark VARCHAR(255) NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_price_provider_model (provider, model)
);

-- ===================== 素材库（一期） =====================
-- 用户素材文件夹（树形；parent_id 为 NULL 表示根目录）
CREATE TABLE IF NOT EXISTS asset_folder (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '属主用户',
  name VARCHAR(64) NOT NULL COMMENT '文件夹名',
  parent_id BIGINT NULL COMMENT '父文件夹；NULL=根目录',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_folder_user_parent_name (user_id, parent_id, name) COMMENT '同层防重名'
) COMMENT '用户素材文件夹';

-- 用户素材（任务提交的图片自动登记；URL 全部来自本系统 OSS，已过白名单校验，不转存）
CREATE TABLE IF NOT EXISTS user_asset (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '属主用户',
  type VARCHAR(16) NOT NULL DEFAULT 'IMAGE' COMMENT 'IMAGE / VIDEO（预留）',
  source VARCHAR(16) NOT NULL DEFAULT 'TASK' COMMENT '来源：TASK 任务提交（UPLOAD 独立上传预留）',
  url VARCHAR(512) NOT NULL COMMENT 'OSS 地址',
  task_id VARCHAR(128) NULL COMMENT '来源任务 video_task.task_id；独立上传时为空',
  folder_id BIGINT NULL COMMENT '所属文件夹；NULL=未归档',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DELETED（软删，保历史任务引用）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_asset_user_time (user_id, id) COMMENT '游标分页',
  UNIQUE KEY uk_asset_user_url (user_id, url) COMMENT '同图幂等去重'
) COMMENT '用户素材库';
