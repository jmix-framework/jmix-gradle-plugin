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

package io.jmix.gradle.runner.clients;

import com.jcraft.jsch.JSchException;
import io.jmix.gradle.runner.CloudClient;
import io.jmix.gradle.runner.InstanceState;
import io.jmix.gradle.runner.ReflectionUtils;
import io.jmix.gradle.runner.ssh.SshSession;

import javax.inject.Inject;
import javax.inject.Named;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public abstract class AbstractCloudClient implements CloudClient {

    @Inject
    protected InstanceState state;

    public InstanceState state() {
        return state;
    }

    private Map<String, Object> environment() {
        Map<String, Object> environment = new HashMap<>();
        for (Field field : ReflectionUtils.getAllFields(this)) {
            Named named = field.getAnnotation(Named.class);
            if (named != null) {
                String propertyName = named.value().isEmpty() ? field.getName() : named.value();
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                try {
                    Object value = field.get(this);
                    if (value != null) {
                        environment.put(propertyName, value);
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Unable to get environment variable value from field " + field.getName());
                }
            }
        }
        return environment;
    }

    public final void createResources() throws Exception {
        doCreateResources();
        state.setEnvironment(environment());
    }

    protected abstract void doCreateResources() throws Exception;

    @Override
    public SshSession ssh() throws JSchException {
        return SshSession.forInstance(state);
    }
}
