/*
 * Copyright (c) 2018, Sebastian Davids
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.sdavids.commons.test;

import static java.lang.Thread.currentThread;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.create;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class WithThreadExtension
    implements AfterEachCallback, BeforeEachCallback, ParameterResolver {

  private static final Namespace WITH_THREAD = create(WithThreadExtension.class);
  private static final String THREAD = "thread";

  @Retention(RUNTIME)
  @Target(PARAMETER)
  public @interface WithThread {}

  @Override
  public void beforeEach(ExtensionContext context) {
    Thread thread =
        new Thread(
            () -> {
              while (!currentThread().isInterrupted()) {
                try {
                  MILLISECONDS.sleep(10L);
                } catch (InterruptedException e) {
                  // allow thread to exit
                }
              }
            },
            context.getDisplayName());
    thread.start();

    context.getStore(WITH_THREAD).put(THREAD, thread);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    Thread thread = context.getStore(WITH_THREAD).remove(THREAD, Thread.class);

    if (thread == null) {
      return;
    }

    MockServices.setServicesForThread(thread);

    thread.interrupt();
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {

    return parameterContext.isAnnotated(WithThread.class);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {

    if (Thread.class.equals(parameterContext.getParameter().getType())) {
      return extensionContext.getStore(WITH_THREAD).get(THREAD, Thread.class);
    }

    throw new ParameterResolutionException(
        "Not a Thread: " + parameterContext.getParameter().getType());
  }
}
