package io.pdfkit.core;

import java.util.List;

/** 参数定义。type 决定 UI 控件，也让校验可以统一在 JobQueue 里做。 */
public record Param(String key, String label, Type type, String def, List<String> choices, String help) {

    public enum Type { TEXT, NUMBER, CHOICE, BOOL, PASSWORD }

    public Param {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public static Param text(String key, String label, String def) {
        return new Param(key, label, Type.TEXT, def, null, null);
    }

    public static Param text(String key, String label, String def, String help) {
        return new Param(key, label, Type.TEXT, def, null, help);
    }

    public static Param num(String key, String label, String def, String help) {
        return new Param(key, label, Type.NUMBER, def, null, help);
    }

    public static Param bool(String key, String label, boolean def, String help) {
        return new Param(key, label, Type.BOOL, Boolean.toString(def), null, help);
    }

    public static Param choice(String key, String label, String def, String... choices) {
        return new Param(key, label, Type.CHOICE, def, List.of(choices), null);
    }

    public static Param password(String key, String label, String help) {
        return new Param(key, label, Type.PASSWORD, "", null, help);
    }
}
