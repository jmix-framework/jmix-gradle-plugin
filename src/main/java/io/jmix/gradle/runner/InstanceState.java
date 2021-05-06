package io.jmix.gradle.runner;

import java.util.HashMap;
import java.util.Map;

public class InstanceState {

    private String provider;
    private String host;
    private String username;
    private String keyFile;
    private Map<String, Object> environment = new HashMap<>();

    public InstanceState() {
    }

    public InstanceState(String provider) {
        this.provider = provider;
    }

    public InstanceState(String provider, String host, String username, String keyFile) {
        this.provider = provider;
        this.host = host;
        this.username = username;
        this.keyFile = keyFile;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getKeyFile() {
        return keyFile;
    }

    public void setKeyFile(String keyFile) {
        this.keyFile = keyFile;
    }

    public Map<String, Object> getEnvironment() {
        return environment;
    }

    public void setEnvironment(Map<String, Object> environment) {
        this.environment = environment;
    }

    public void addEnvironmentVariable(String key, Object value) {
        this.environment.put(key, value);
    }

    public Object getEnvironmentVariable(String key) {
        return this.environment.get(key);
    }

    public boolean hasEnvironmentVariable(String key) {
        return this.environment.containsKey(key);
    }
}
