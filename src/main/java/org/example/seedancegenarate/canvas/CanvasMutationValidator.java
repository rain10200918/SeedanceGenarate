package org.example.seedancegenarate.canvas;

/**
 * 画布保存校验规则。**扩展点：新增一个 {@code @Component} 实现即多一条规则** ——
 * Spring 注入全部实现，CanvasService 在落库前逐个跑，主流程零改动。
 * <p>
 * 约定：不合法就抛 {@code BusinessException}。所有校验跑完才会动 CAS 与写库，
 * 所以抛异常时画布一定还是原样（不会留下改了一半的状态）。
 */
public interface CanvasMutationValidator {

    void validate(CanvasMutationContext context);
}
