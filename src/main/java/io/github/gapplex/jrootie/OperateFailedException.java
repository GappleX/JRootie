package io.github.gapplex.jrootie;

public class OperateFieldFailedException extends RuntimeException {
    public OperateFieldFailedException(String message) {
        super(message);
    }
    public OperateFieldFailedException(String message, Throwable t) {
        super(message, t);
    }
}
