package org.openshell.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class HostConfig implements Serializable {
    private String id;
    private String name;
    private String group = "默认分组";
    private String host;
    private int port = 22;
    private String username = "root";
    private String authType = "PASSWORD"; // PASSWORD or KEY
    private String password;
    private String keyPath;
    private String keyPassphrase;
    private String charset = "UTF-8";
    private String remarks;
    private long lastConnected;

    public HostConfig() {
        this.id = UUID.randomUUID().toString();
    }

    public HostConfig(String name, String host, int port, String username) {
        this();
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return (name == null || name.trim().isEmpty()) ? host : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGroup() {
        return (group == null || group.trim().isEmpty()) ? "默认分组" : group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port <= 0 ? 22 : port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return (username == null || username.trim().isEmpty()) ? "root" : username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getKeyPath() {
        return keyPath;
    }

    public void setKeyPath(String keyPath) {
        this.keyPath = keyPath;
    }

    public String getKeyPassphrase() {
        return keyPassphrase;
    }

    public void setKeyPassphrase(String keyPassphrase) {
        this.keyPassphrase = keyPassphrase;
    }

    public String getCharset() {
        return charset == null ? "UTF-8" : charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public long getLastConnected() {
        return lastConnected;
    }

    public void setLastConnected(long lastConnected) {
        this.lastConnected = lastConnected;
    }

    public HostConfig copy() {
        HostConfig snapshot = new HostConfig();
        snapshot.id = id; snapshot.name = name; snapshot.group = group;
        snapshot.host = host; snapshot.port = port; snapshot.username = username;
        snapshot.authType = authType; snapshot.password = password;
        snapshot.keyPath = keyPath; snapshot.keyPassphrase = keyPassphrase;
        snapshot.charset = charset; snapshot.remarks = remarks; snapshot.lastConnected = lastConnected;
        return snapshot;
    }

    @Override
    public String toString() {
        return getName() + " (" + getHost() + ")";
    }
}
