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

import com.google.common.collect.ImmutableMap;
import io.jmix.gradle.runner.clients.AwsCloudClient;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public final class CloudClientFactory {

    private static final Map<String, Class<? extends CloudClient>> PROVIDER_CLIENTS =
            ImmutableMap.<String, Class<? extends CloudClient>>builder()
                    .put("aws", AwsCloudClient.class)
                    .build();

    public static CloudClient fromState(InstanceState state, Task task, String outputDir) {
        return create(state.getProvider(), task, state, outputDir);
    }

    public static CloudClient create(String provider, Task task, String outputDir) {
        return create(provider, task, new InstanceState(provider), outputDir);
    }

    public static CloudClient create(String provider, Task task, InstanceState state, String outputDir) {
        Class<? extends CloudClient> clientClass = PROVIDER_CLIENTS.get(provider);
        if (clientClass == null) {
            throw new IllegalArgumentException("Unknown provider " + provider);
        }
        CloudClient client = null;
        try {
            client = clientClass.newInstance();
            injectProperties(client, task, state, outputDir);
            postConstruct(client);
        } catch (Exception e) {
            throw new RuntimeException("Error instantiating client object", e);
        }
        return client;
    }

    private static void injectProperties(CloudClient client, Task task, InstanceState state, String outputDir)
            throws IllegalAccessException {
        for (Field field : ReflectionUtils.getAllFields(client)) {
            Inject inject = field.getAnnotation(Inject.class);
            if (inject != null) {
                Class<?> fieldType = field.getType();
                Object value = null;
                if (Task.class.isAssignableFrom(fieldType)) {
                    value = task;
                } else if (Project.class.isAssignableFrom(fieldType)) {
                    value = task.getProject();
                } else if (Logger.class.isAssignableFrom(fieldType)) {
                    value = task.getLogger();
                } else if (InstanceState.class.isAssignableFrom(fieldType)) {
                    value = state;
                }
                ReflectionUtils.injectFieldValue(field, client, value);
            }
            Named named = field.getAnnotation(Named.class);
            if (named != null) {
                String propertyName = named.value().isEmpty() ? field.getName() : named.value();
                Object propertyValue = null;
                if ("outputDir".equalsIgnoreCase(propertyName)) {
                    propertyValue = outputDir;
                } else if (state.hasEnvironmentVariable(propertyName)) {
                    propertyValue = state.getEnvironmentVariable(propertyName);
                } else if (task.hasProperty(propertyName)) {
                    propertyValue = task.property(propertyName);
                } else if (task.getProject().hasProperty(propertyName)) {
                    propertyValue = task.getProject().property(propertyName);
                }
                ReflectionUtils.injectFieldValue(field, client, propertyValue);
            }
        }
    }

    private static void postConstruct(CloudClient client) throws InvocationTargetException, IllegalAccessException {
        for (Method method : client.getClass().getMethods()) {
            if (method.getAnnotation(PostConstruct.class) != null) {
                if (method.getParameters().length > 0) {
                    throw new IllegalStateException("Method annotated with @PostConstruct should not have any parameters");
                }
                method.invoke(client);
            }
        }
    }
}
