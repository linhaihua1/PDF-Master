package io.pdfkit.core;

/**
 * 一项能力的执行体。
 *
 * <p>这是整个架构里唯一的「逻辑入口」。实现类只处理 PDF 逻辑——不读界面、不碰任务队列、
 * 不写日志、不管线程。这种克制是刻意的：
 *
 * <ul>
 *   <li><b>可以脱离界面测试</b> —— 测试直接 {@code new Ctx(...)} 然后调 {@code run()},
 *       60 项能力的用例全部不启 UI，跑一轮只要几秒。</li>
 *   <li><b>依赖方向单向</b> —— {@code ui → core → ops → util}。ops 里 import 任何 ui 包
 *       都算架构违规。</li>
 *   <li><b>换界面不用动业务</b> —— 前端从 Swing 换成别的东西时，ops 一行不用改。</li>
 * </ul>
 *
 * <p><b>实现约定</b>：
 * <ol>
 *   <li>开头先 {@code ctx.input()}（或 {@code ctx.input(i)}）取输入。这一步同时起校验作用，
 *       空输入会抛 {@code ERR_NO_INPUT}。要让输入校验先于参数校验发生——否则用户没选文件
 *       却先收到「页码格式不对」，提示顺序反了。</li>
 *   <li>把产物路径 {@code ctx.addOutput(...)} 登记上，界面靠这个列表展示「生成了什么」。
 *       不登记的话文件其实写出来了，但用户看不到、也找不到。</li>
 *   <li>分析类能力（只出文字不出文件）用 {@code ctx.setResult(...)} 放结果，不必落盘。</li>
 *   <li>耗时操作在关键节点 {@code ctx.report(pct, "正在做什么")}，界面进度条才不会假死；
 *       它内部还会检查取消令牌，所以长循环里调它等于顺便实现了「取消」。</li>
 *   <li>业务错误抛 {@link Err}，别抛裸 {@code RuntimeException}——后者到界面会变成
 *       「未知错误」，用户没法自己判断该改什么。</li>
 * </ol>
 */
public interface Op {

    /**
     * 执行能力。
     *
     * @param ctx 执行上下文，提供输入文件、参数、输出目录、临时目录、进度与取消令牌
     * @throws Err    业务错误，带错误码，界面直接展示 message
     * @throws Exception 其它底层异常（IO、PDFBox），由队列兜底包装成 {@code ERR_UNEXPECTED}
     */
    void run(Ctx ctx) throws Exception;
}
