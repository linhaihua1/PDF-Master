package io.pdfkit.core;

import java.util.List;

/**
 * 参数定义：描述「这个能力需要用户填什么」。
 *
 * <p>这是 UI 自动生成表单的唯一依据。{@link #type()} 决定渲染成什么控件，
 * {@link #help()} 显示成控件下方或悬浮的说明。能力实现里通过
 * {@link Ctx#str(String)} / {@link Ctx#intVal(String, int)} 等方法按 {@link #key()}
 * 取值，两条路径共用同一个 key，改 key 时两边必须同步。
 *
 * <p>这里只声明「形状」，不持有「值」。用户填的值在运行时由
 * {@link io.pdfkit.ui.FormPanel} 收集成 {@code Map<String,String>} 交给 {@link JobQueue}。
 *
 * @param key     参数键，能力实现里用这个 key 读值。同一能力内唯一。
 * @param label   界面显示名，如「页码位置」。
 * @param type    控件类型，见 {@link Type}。
 * @param def     默认值。统一存成字符串——这样表单初始化、参数校验、执行时读取
 *                只有一种表示，不必为每种类型写一遍转换。
 * @param choices 仅 {@link Type#CHOICE} 使用，下拉框的候选值。
 * @param help    帮助文本，说明这个参数的单位、取值范围或注意事项。
 */
public record Param(String key, String label, Type type, String def, List<String> choices, String help) {

    /**
     * 控件类型。加新类型时要同步改两处：{@link io.pdfkit.ui.FormPanel} 里生成控件的地方，
     * 以及 {@link JobQueue} 里做统一校验的地方。
     */
    public enum Type {
        /** 单行文本框。 */
        TEXT,
        /** 数字框。执行时用 {@link Ctx#intVal} 解析，填非数字会得到 {@code ERR_BAD_PARAM}。 */
        NUMBER,
        /** 下拉框，候选值来自 {@link Param#choices()}。 */
        CHOICE,
        /** 勾选框，值序列化为 {@code "true"} / {@code "false"}。 */
        BOOL,
        /** 密码框，界面上回显为圆点，且不写入任何日志（见 {@code Log} 的脱敏处理）。 */
        PASSWORD
    }

    /**
     * 紧凑构造：choices 为 null 时给空列表，避免调用方到处判空；
     * 同时做不可变副本，防止外部改掉候选值导致界面与校验不一致。
     */
    public Param {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /** 普通文本框，无帮助文本。 */
    public static Param text(String key, String label, String def) {
        return new Param(key, label, Type.TEXT, def, null, null);
    }

    /** 普通文本框，带帮助文本。 */
    public static Param text(String key, String label, String def, String help) {
        return new Param(key, label, Type.TEXT, def, null, help);
    }

    /** 数字框。默认值也写成字符串，执行时统一解析。 */
    public static Param num(String key, String label, String def, String help) {
        return new Param(key, label, Type.NUMBER, def, null, help);
    }

    /** 勾选框，把 boolean 默认值转成字符串存起来。 */
    public static Param bool(String key, String label, boolean def, String help) {
        return new Param(key, label, Type.BOOL, Boolean.toString(def), null, help);
    }

    /** 下拉框，候选值用可变参数传入。 */
    public static Param choice(String key, String label, String def, String... choices) {
        return new Param(key, label, Type.CHOICE, def, List.of(choices), null);
    }

    /**
     * 密码框。默认值固定空串——不预填任何密码，避免「默认密码」这种危险配置
     * 被用户忽略而直接执行。
     */
    public static Param password(String key, String label, String help) {
        return new Param(key, label, Type.PASSWORD, "", null, help);
    }
}
