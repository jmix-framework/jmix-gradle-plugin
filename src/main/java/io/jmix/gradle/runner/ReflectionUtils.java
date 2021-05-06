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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ReflectionUtils {

    public static <T> List<Field> getAllFields(T obj) {
        List<Field> fields = new ArrayList<>();
        Class<?> aClass = obj.getClass();
        while (aClass != Object.class) {
            fields.addAll(Arrays.asList(aClass.getDeclaredFields()));
            aClass = aClass.getSuperclass();
        }
        return fields;
    }

    public static void injectFieldValue(Field field, Object object, Object value) throws IllegalAccessException {
        if (value != null) {
            if (!field.isAccessible()) {
                field.setAccessible(true);
            }
            field.set(object, value);
        }
    }

    public static Object getFieldValue(Field field, Object object) {
        if (!field.isAccessible()) {
            field.setAccessible(true);
        }
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Unable to get environment variable value from field " + field.getName());
        }
    }
}
