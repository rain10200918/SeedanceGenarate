package org.example.seedancegenarate.service;

import org.example.seedancegenarate.dto.ModelAccessView;

import java.util.List;
import java.util.Map;

/**
 * 模型开放策略：管理员可运行时开 / 关每个模型。开关规则的唯一权威——
 * {@code /options} 过滤、提交校验、后台管理都只认这个接口（参照 {@link PricingService} 的抽象方式）。
 * <p>
 * 「有哪些模型」以 {@code VideoEngineRegistry} 为准；本服务只叠加每个模型的开关覆盖，
 * 没有覆盖的模型走默认（{@code video.model-access.default-open}）。
 */
public interface ModelAccessService {

    /** 该模型当前是否开放（普通用户可见可用）。没有显式覆盖时走默认值；model 为空视为不拦截。 */
    boolean isOpen(String model);

    /**
     * 当前全量开关覆盖（model → enabled），只含显式设过的模型。
     * 供需要一次判断多个模型的调用方（如 {@code /options}）拿完再自己查，
     * 避免每个模型调一次 {@link #isOpen(String)}。
     */
    Map<String, Boolean> currentOverrides();

    /** 无显式覆盖时的默认开关（{@code video.model-access.default-open}）。 */
    boolean defaultOpen();

    /** 后台：所有已注册模型 + 当前开关态。 */
    List<ModelAccessView> listAll();

    /** 后台：设置某模型开 / 关（不存在则新增覆盖行）。 */
    void setOpen(String model, boolean open);
}
