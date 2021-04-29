package io.jmix.gradle.runner;

import org.gradle.api.Task;

import java.io.File;

public interface CloudClient {

    void init(Task task, File tmpDir);
    ComputeInstance create() throws Exception;
    void destroy(ComputeInstance instance) throws Exception;
}
