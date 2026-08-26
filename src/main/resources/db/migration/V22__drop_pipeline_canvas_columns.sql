-- 撤回 V20：画布不再寄生在分镜流水线上
--
-- 背景：V20 曾把画布做成「pipeline 的另一种 kind」——给 pipeline / pipeline_node 加画布列、
-- 另起 pipeline_edge 存连线。产品上这是错的：画布节点有自己的类型（素材/文本/生成）、
-- 有按模型能力推导的输入输出端口，连线要带端口，和「一条线性分镜流水」不是一回事。
-- V21 已给画布建了独立的 canvas / canvas_node / canvas_edge 三表，本迁移把 V20 的寄生痕迹全部撤掉，
-- 让 pipeline 回到 V18 的形状：没有死列，分镜流水线只管分镜流水线。
--
-- 为什么是「前进式撤销」而不是删掉 V20 文件：V20 已在开发库落地并记入 flyway_schema_history，
-- 删文件会让 Flyway 校验失败（Detected applied migration not resolved locally）。
-- 在从未跑过 V20 的库上，V20 与 V22 同批执行，净效果为零。

DROP TABLE IF EXISTS pipeline_edge;

-- 先显式删唯一键：MySQL 会随最后一列自动删索引，但写出来才看得见「这个约束也一起走了」
ALTER TABLE pipeline_node
    DROP INDEX uk_node_pipeline_key;

ALTER TABLE pipeline_node
    DROP COLUMN node_key,
    DROP COLUMN pos_x,
    DROP COLUMN pos_y,
    DROP COLUMN width,
    DROP COLUMN height,
    DROP COLUMN metadata;

ALTER TABLE pipeline
    DROP COLUMN kind,
    DROP COLUMN viewport,
    DROP COLUMN canvas_version,
    DROP COLUMN last_mutation_id;
