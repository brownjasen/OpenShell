package org.openshell.session;

public enum SessionState {
    DISCONNECTED("未连接"), CONNECTING("连接中"), CONNECTED("已连接"),
    RECONNECTING("重连中"), FAILED("连接失败"), CLOSED("已关闭");
    private final String label;
    SessionState(String label) { this.label = label; }
    public String label() { return label; }
}
