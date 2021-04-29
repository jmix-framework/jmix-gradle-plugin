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

package io.jmix.gradle.runner;

import io.jmix.gradle.runner.clients.AwsCloudClient;
import org.gradle.api.Task;

import java.io.File;

public final class CloudClientFactory {

    public static CloudClient forProvider(String provider, Task task, File tmpDir) {
        CloudClient client = forProvider(provider);
        client.init(task, tmpDir);
        return client;
    }

    public static CloudClient forProvider(String provider) {
        if (provider.equals("aws")) {
            return new AwsCloudClient();
        }
        throw new IllegalArgumentException("Unknown provider " + provider);
    }
}
