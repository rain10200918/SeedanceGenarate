package org.example.seedancegenarate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户端模型与算力定价视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPricingView {
    /** 模型标识，如 minimax-h3, flux2-image-edit */
    private String model;
    /** 模型展示名称，如 MiniMax H3 */
    private String label;
    /** 提供方标识，如 seedance, comfyui */
    private String provider;
    /** 提供方展示名称，如 Seedance 官方引擎 */
    private String providerName;
    /** 输出形态：VIDEO / IMAGE */
    private String outputType;
    /** 计费类型：PER_SECOND / FLAT */
    private String billingType;
    /** 单价（元） */
    private BigDecimal unitPrice;
    /** 算力点单价（1元 = 100算力点） */
    private Long pointsPerUnit;
    /** 算力计费文案，如 "20 算力点 / 秒" 或 "1 算力点 / 次" */
    private String pricingText;
    /** 支持的画幅比例 */
    private List<String> ratios;
    /** 支持的时长列表（秒） */
    private List<Integer> durations;
    /** 支持的分辨率档位（百万像素） */
    private List<Double> megapixels;
    /** 最小输入图片数 */
    private Integer imageMin;
    /** 最大输入图片数 */
    private Integer imageMax;
    /** 是否必须传图 */
    private Boolean needImages;
    /** 最大支持输入视频数 */
    private Integer videoMax;
    /** 最大支持输入音频数 */
    private Integer audioMax;
    /** 是否图或视频二选一 */
    private Boolean needImageOrVideo;
    /** 是否对普通用户开放 */
    private Boolean open;
    /** 模型功能介绍与特点描述 */
    private String description;
    /** 模型特性标签列表 */
    private List<String> tags;
}
