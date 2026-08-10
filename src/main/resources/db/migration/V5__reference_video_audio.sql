-- 多参考生视频模型（MiniMax-H3 4-step）的参考视频 / 参考音频持久化，
-- 供历史详情展示与「用这些重新生成」复用（参考图片已有 images 列）。

ALTER TABLE video_task
  ADD COLUMN reference_videos TEXT NULL COMMENT '参考视频 OSS URL 的 JSON 数组，顺序对应 <Video 1..N>' AFTER images,
  ADD COLUMN reference_audios TEXT NULL COMMENT '参考音频 OSS URL 的 JSON 数组，顺序对应 <Audio 1..N>' AFTER reference_videos;
