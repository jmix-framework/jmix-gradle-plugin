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

package io.jmix.gradle.runner

import com.fasterxml.jackson.databind.ObjectMapper
import io.jmix.gradle.runner.docker.DockerUtils
import io.jmix.gradle.runner.ssh.SshSession
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

import java.util.zip.GZIPOutputStream

import static io.jmix.gradle.runner.docker.DockerUtils.local

class CloudRun extends DefaultTask {

    private ObjectMapper objectMapper

    private String provider = 'aws'
    private boolean forceRecreate = false

    @Input
    String getProvider() {
        return provider
    }

    @Option(option = 'provider', description = 'Configures cloud provider where application should be deployed.')
    void setProvider(String provider) {
        this.provider = provider
    }

    @Input
    boolean getForceRecreate() {
        return forceRecreate
    }

    @Option(option = 'forceRecreate', description = 'Configures whether task should always recreate cloud instance')
    void setForceRecreate(boolean forceRecreate) {
        this.forceRecreate = forceRecreate
    }

    CloudRun() {
        dependsOn("bootBuildImage")
        setGroup("application")
        setDescription("Runs Jmix project in cloud environment")

        objectMapper = new ObjectMapper()
    }

    @TaskAction
    void run() {
        Directory tmpDir = project.layout.buildDirectory.dir("tmp/jmixCloudRun").get()
        if (!tmpDir.asFile.exists()) {
            tmpDir.asFile.mkdirs()
        }
        File file = tmpDir.file('compute-instance.json').asFile
        ComputeInstance instance = null
        boolean createInstance = true

        if (file.exists()) {
            instance = objectMapper.readValue(file, ComputeInstance.class)

            try (SshSession ssh = SshSession.forComputeInstance(instance)) {
                ssh.execute("cd app && docker-compose kill")
            }

            if (forceRecreate) {
                logger.lifecycle("Destroying current instance $instance.host")
                CloudClientFactory.forProvider(instance.getProvider(), this, tmpDir.asFile).destroy(instance)
                logger.lifecycle("Successfully destroyed instance $instance.host")
            } else {
                createInstance = false
            }
        }

        if (createInstance) {
            logger.lifecycle("Creating instance using $provider provider")
            instance = CloudClientFactory.forProvider(provider, this, tmpDir.asFile).create()
            objectMapper.writeValue(file, instance)
            logger.lifecycle("Successfully created instance $instance.host")
        }

        String imageName = project.tasks.bootBuildImage.imageName
        String imageArchiveName = "${imageName.replaceAll("[/:]", "-")}.tar.gz"
        File imageArchiveFile = tmpDir.file(imageArchiveName).asFile
        logger.lifecycle("Saving Docker image to file $imageArchiveName")
        try (InputStream dockerImageStream = DockerUtils.saveImage(local(), imageName)) {
            gzip(dockerImageStream, imageArchiveFile)
        }
        logger.lifecycle("Successfully saved Docker image ")

        runDockerCompose(instance, imageArchiveFile)

        logger.quiet("Application is running on http://$instance.host:8080")
    }

    private void runDockerCompose(ComputeInstance instance, File imageFile) {
        try (SshSession ssh = SshSession.forComputeInstance(instance)) {
            String imageName = project.tasks.bootBuildImage.imageName

            ssh.execute("mkdir app")

            logger.lifecycle("Uploading image file")
            ssh.scpUploadFile(imageFile, "app/$imageFile.name")
            logger.lifecycle("Uploaded image file")

            logger.lifecycle("Uploading docker-compose file")
            ssh.scpUploadFile(project.file("docker-compose.yml"), "app/docker-compose.yml")
            logger.lifecycle("Successfully uploaded docker-compose file")

            logger.lifecycle("Loading image $imageName from file $imageFile.name")
            ssh.execute("cd app && gunzip -c $imageFile.name | docker load")
            logger.lifecycle("Successfully loaded image $imageName")

            logger.lifecycle("Starting application")
            ssh.execute("cd app && docker-compose up -d")
        }
    }

    static void gzip(InputStream inputStream, File file) {
        try (FileOutputStream fOut = new FileOutputStream(file)
             GZIPOutputStream gzipOut = new GZIPOutputStream(fOut)) {
            byte[] buf = new byte[16 * 1024]
            int length
            while ((length = inputStream.read(buf)) > 0) {
                gzipOut.write(buf, 0, length)
            }
        }
    }
}
