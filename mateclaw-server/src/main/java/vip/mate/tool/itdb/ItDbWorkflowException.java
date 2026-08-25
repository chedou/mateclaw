package vip.mate.tool.itdb;

final class ItDbWorkflowException extends RuntimeException {

    private final String code;

    ItDbWorkflowException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    String code() {
        return code;
    }
}
