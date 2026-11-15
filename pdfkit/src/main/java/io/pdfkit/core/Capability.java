package io.pdfkit.core;

import java.util.List;

/**
 * 能力声明：注册表里的一项。
 *
 * <p>这是整个应用的中枢数据结构。一份 {@code Capability} 同时承担三个职责：
 * <ol>
 *   <li><b>能力清单</b> —— 注册表（{@link Registry}）持有一份 List，UI 左侧树由它构建，
 *       分级依据是 {@link #category()}。</li>
 *   <li><b>表单定义</b> —— UI 完全由 {@link #params()} 驱动生成控件，界面上没有任何一处
 *       硬编码某个能力的输入框。新增能力不需要改 UI 代码，这是「60 项能力只用一套界面」
 *       的前提。</li>
 *   <li><b>执行入口</b> —— {@link #op()} 是真正干活的实现，只处理 PDF 逻辑，
 *       不知道界面、队列、日志的存在（依赖方向 {@code ui → core → ops → util} 单向）。</li>
 * </ol>
 *
 * <p>用 {@code record} 而非普通类：能力声明是不可变的元数据，创建后不该被修改。
 * 这也让测试可以放心地把整张注册表当只读快照用。
 *
 * @param id       稳定标识，形如 {@code organize.merge}。约定是「分类.动作」，
 *                 一旦发布就不能改——它被测试用例、日志、错误码引用。全表唯一，
 *                 {@link Registry} 在启动时校验重复。
 * @param title    界面显示的名称，如「合并 PDF」。可以随时改，不影响任何逻辑。
 * @param category 左侧树的分组名，如「页面组织」。同一 category 的能力归到同一个父节点下，
 *                 顺序由首次出现的先后决定。
 * @param desc     一句话说明，显示在表单顶部，也用于提示用户这个功能会产出什么。
 * @param params   参数定义列表，决定表单长什么样。可以为空（无参数的能力）。
 *                 实际传值不走这里，而是运行时由用户填到 {@link Ctx#params()} 里。
 * @param op       执行体。UI 线程只负责把它交给 {@link JobQueue}，在后台线程调
 *                 {@link Op#run(Ctx)}。
 */
public record Capability(
        String id,
        String title,
        String category,
        String desc,
        List<Param> params,
        Op op
) {

    /**
     * 紧凑构造：只做防 null 与防外部修改两件事。
     *
     * <p>{@code params == null} 时给空列表而不是抛异常，是为了让「无参数能力」的声明
     * 写起来更省事（{@code null} 和 {@code List.of()} 都接受）。
     * {@code List.copyOf} 既挡 null 元素，也生成不可变副本——防止调用方后续往原列表里
     * 加参数导致界面与实际执行对不上。
     */
    public Capability {
        params = params == null ? List.of() : List.copyOf(params);
    }
}
