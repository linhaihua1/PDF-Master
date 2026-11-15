package io.pdfkit.core;

/** 业务异常：带稳定错误码，UI 直接显示 message，日志记 code + cause。 */
public class Err extends RuntimeException {

    private final String code;

    public Err(String code, String message) {
        super(message);
        this.code = code;
    }

    public Err(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Err of(String code, String fmt, Object... args) {
        return new Err(code, String.format(fmt, args));
    }

    @Override
    public String toString() {
        return "[" + code + "] " + getMessage();
    }
}
