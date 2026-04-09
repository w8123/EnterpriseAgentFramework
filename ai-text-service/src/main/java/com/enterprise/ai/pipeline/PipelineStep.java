package com.enterprise.ai.pipeline;

/**
 * Pipeline 步骤接口 — 流水线中每个可插拔节点的统一抽象。
 *
 * <p>每个实现类代表入库流程中的一个独立阶段（解析、清洗、切分、向量化等），
 * 通过 {@link PipelineContext} 在步骤间传递数据。</p>
 *
 * <h3>扩展方式</h3>
 * <ol>
 *   <li>实现此接口</li>
 *   <li>注册为 Spring Bean</li>
 *   <li>在 pipeline 配置中引用步骤名称</li>
 * </ol>
 */
public interface PipelineStep {

    /**
     * 执行当前步骤的处理逻辑。
     *
     * @param context 流水线上下文，贯穿整个 pipeline 生命周期
     * @throws PipelineException 当处理失败且需要中断流水线时抛出
     */
    void process(PipelineContext context);

    /**
     * 返回步骤名称（用于日志与配置匹配）
     */
    String getName();
}
