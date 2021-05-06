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
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.tasks.TaskAction

class CloudClean extends DefaultTask {

    private ObjectMapper objectMapper

    CloudClean() {
        setGroup("application")
        setDescription("Destroys currently used cloud environment")

        objectMapper = new ObjectMapper()
    }

    @TaskAction
    void destroy() {
        Directory outDir = project.layout.buildDirectory.dir("tmp/jmixCloudRun").get()
        if (outDir.asFile.exists()) {
            File file = outDir.file('compute-instance.json').asFile
            if (file.exists()) {
                InstanceState instance = objectMapper.readValue(file, InstanceState.class)
                CloudClientFactory.fromState(instance, this, outDir.asFile.path).destroyResources()
            }
            FileUtils.deleteDirectory(outDir.asFile)
        }
    }
}
