/*
 * Copyright 2021 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jmix.gradle.runner.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.SaveImageCmd;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import java.io.InputStream;

public final class DockerUtils {

    public static DockerClient client(DockerClientConfig config) {
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(10)
                .build();

        return DockerClientBuilder.getInstance(config)
                .withDockerHttpClient(httpClient)
                .build();
    }

    public static DockerClient local() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(10)
                .build();

        return DockerClientBuilder.getInstance(config)
                .withDockerHttpClient(httpClient)
                .build();
    }

    public static InputStream saveImage(DockerClient client, String imageName) {
        String name = imageName;
        String tag = null;
        int delimiter = imageName.lastIndexOf(':');
        if (delimiter >= 0) {
            name = imageName.substring(0, delimiter);
            tag = imageName.substring(delimiter + 1);
        }

        SaveImageCmd saveImageCmd = client.saveImageCmd(name);
        if (tag != null) {
            saveImageCmd.withTag(tag);
        }

        return saveImageCmd.exec();
    }

}
