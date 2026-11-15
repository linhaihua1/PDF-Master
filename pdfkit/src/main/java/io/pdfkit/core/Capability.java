package io.pdfkit.core;

import java.util.List;

/** 能力声明：注册表里的一项，UI 完全由 params 驱动生成表单。 */
public record Capability(
        String id,
        String title,
        String category,
        String desc,
        List<Param> params,
        Op op
) {
    public Capability {
        params = params == null ? List.of() : List.copyOf(params);
    }
}
