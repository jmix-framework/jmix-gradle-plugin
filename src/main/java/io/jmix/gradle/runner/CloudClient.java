package io.jmix.gradle.runner;

import com.jcraft.jsch.JSchException;
import io.jmix.gradle.runner.ssh.SshSession;

public interface CloudClient {

    void createResources() throws Exception;
    void destroyResources() throws Exception;
    InstanceState state();
    SshSession ssh() throws JSchException;
}
