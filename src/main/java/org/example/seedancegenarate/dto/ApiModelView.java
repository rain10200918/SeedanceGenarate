package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * 对外 API 的模型清单项（GET /api/v1/models）：能力约束与 UI /options 同源（ModelSpec），
 * 开关过滤同规则（关闭的不下发；属主是管理员则全部可见带 open 标记）。
 */
public record ApiModelView(
        String model,
        String label,
        String provider,
        String outputType,
        boolean needImages,
        int imageMin,
        int imageMax,
        List<String> ratios,
        List<Integer> durations,
        List<Double> megapixels,
        boolean open
) {
}
