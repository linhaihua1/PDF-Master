package io.pdfkit.core;

/**
 * 业务异常：带稳定错误码，界面直接显示 {@code message}，日志记 {@code code} + cause。
 *
 * <p><b>为什么是 unchecked</b>：{@link Op#run} 已经声明 {@code throws Exception}，
 * 如果业务错误也做成受检异常，每个能力实现里都会堆满 try-catch 只为转换类型。
 * 继承 {@code RuntimeException} 后，能力实现可以随手 {@code throw Err.of(...)} 走人，
 * 由 {@link JobQueue} 在边界统一捕获、记日志、回填任务状态。
 *
 * <p><b>错误码约定</b>：{@code ERR_} 前缀 + 大写下划线短语，全项目复用，不按能力各造一套。
 * 因为错误码是测试断言与用户提示的共同依据，同一个语义在不同能力里用同一个码，
 * 测试才能写成「任何能力空输入都必须得到 ERR_NO_INPUT」这种通用断言。
 *
 * <p><b>现有错误码分类</b>（新增时先看能不能复用，别造同义词）：
 * <table border="1">
 *   <caption>错误码语义分组</caption>
 *   <tr><th>分组</th><th>错误码</th></tr>
 *   <tr><td>输入缺失</td><td>ERR_NO_INPUT、ERR_NO_FILE、ERR_NEED_TWO、ERR_TOO_FEW</td></tr>
 *   <tr><td>输入内容不合法</td><td>ERR_BAD_PDF、ERR_BAD_XML、ERR_BAD_IMAGE、ERR_BAD_CERT、
 *       ERR_BAD_REGEX、ERR_BAD_PAGE_SPEC、ERR_IS_DIR、ERR_EMPTY_OUTPUT</td></tr>
 *   <tr><td>参数不合法</td><td>ERR_BAD_PARAM、ERR_PARAM_RANGE、ERR_PAGE_RANGE</td></tr>
 *   <tr><td>前置条件不满足</td><td>ERR_NO_FORM、ERR_NO_TEXT、ERR_NO_MATCH、ERR_NO_BOOKMARK、
 *       ERR_NO_KEY、ERR_NO_ATTACHMENT、ERR_NO_SIGNATURE、ERR_NO_FIELD_MATCH、
 *       ERR_NO_CJK_FONT、ERR_NO_CERT、ERR_NO_IMAGE、ERR_NO_TIFF、ERR_NO_TITLE</td></tr>
 *   <tr><td>加密状态冲突</td><td>ERR_ENCRYPTED、ERR_NOT_ENCRYPTED、ERR_ALREADY_ENCRYPTED、
 *       ERR_NO_PERM</td></tr>
 *   <tr><td>操作破坏性保护</td><td>ERR_DELETE_ALL、ERR_CROP_TOO_MUCH</td></tr>
 *   <tr><td>运行期失败</td><td>ERR_WRITE、ERR_WRITE_IMAGE、ERR_WORKDIR、ERR_NO_SPACE、
 *       ERR_SIGN_FAILED、ERR_UNEXPECTED、ERR_CANCELED</td></tr>
 * </table>
 *
 * <p><b>message 的硬要求</b>：写给用户看，中文、说清「哪里不对」和「怎么办」，
 * <b>不得</b>出现异常类名、堆栈、绝对路径、内部方法名——{@code SecurityTest} 里有专门
 * 断言扫描这一点。技术细节放到 cause 里交给日志。
 */
public class Err extends RuntimeException {

    /** 稳定错误码，见类注释的分类表。 */
    private final String code;

    /**
     * @param code    错误码，必须是 {@code ERR_} 前缀的已有码
     * @param message 面向用户的中文提示
     */
    public Err(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 需要保留底层异常时用这个构造。
     *
     * <p>cause 只进日志不进界面：{@code JobQueue} 打印 {@code code + message}，
     * 而界面只拿 {@code getMessage()}。这样既留住了排查线索，又不把实现细节暴露给用户。
     *
     * @param cause 底层异常，可为 null
     */
    public Err(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 取错误码，供队列记日志、测试断言使用。 */
    public String code() {
        return code;
    }

    /**
     * 带格式化文案的工厂方法。
     *
     * <p><b>注意</b>：这里走的是 {@link String#format}，文案里如果出现字面的 {@code %}，
     * 必须写成 {@code %%}。踩过一次：参数说明写成「（如 100%）」，{@code %} 后面跟着
     * 全角右括号被当成转换符，调用即抛 {@code UnknownFormatConversionException}。
     * 这类文案建议直接避开百分号，比如写成「调到 100 或更低」。
     */
    public static Err of(String code, String fmt, Object... args) {
        return new Err(code, String.format(fmt, args));
    }

    /**
     * 日志与断言用的短格式：{@code [ERR_NO_INPUT] 请先选择文件}。
     *
     * <p>只加错误码，不拼类名和堆栈——堆栈由日志框架自己打。
     */
    @Override
    public String toString() {
        return "[" + code + "] " + getMessage();
    }
}
