package io.jmix.gradle.runner;

import java.util.HashMap;
import java.util.Map;

public class ComputeInstance {

    private String provider;
    private String host;
    private String username;
    private String keyFile;
    private Map<String, String> resources = new HashMap<>();

    public ComputeInstance() {
    }

    public ComputeInstance(String provider, String host, String username, String keyFile) {
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

    public Map<String, String> getResources() {
        return resources;
    }

    public void setResources(Map<String, String> resources) {
        this.resources = resources;
    }

    public void addResource(String key, String value) {
        this.resources.put(key, value);
    }

    public String getResource(String key) {
        return this.resources.get(key);
    }
}
