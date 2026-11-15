package io.pdfkit.core;

/** 一项能力的执行体。实现类只处理 PDF 逻辑，UI/队列/日志都不关心。 */
public interface Op {
    void run(Ctx ctx) throws Exception;
}
