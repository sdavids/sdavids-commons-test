/*
 * Copyright (c) 2017-2018, Sebastian Davids
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

import static java.util.ServiceLoader.load;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Iterator;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class MockServicesTest {

  @AfterEach
  void tearDown() {
    MockServices.setServices();
  }

  @Test
  void setServices_null() {
    assertThrows(
        NullPointerException.class,
        () -> {
          // noinspection ConstantConditions
          MockServices.setServices((Class<?>[]) null);
        },
        "services");

    assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  void setServices_non_public_no_arg_ctor() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MockServices.setServices(NonPublicNoArgCtorServiceInterface1.class),
        "Class io.sdavids.commons.test.NonPublicNoArgCtorServiceInterface1 has no public no-arg constructor");

    assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  void setServices_abstract() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MockServices.setServices(AbstractServiceInterface1.class),
        "Class io.sdavids.commons.test.AbstractServiceInterface1 must be a public non-abstract class");

    assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  void setServices_non_public() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MockServices.setServices(NonPublicServiceInterface1.class),
        "Class io.sdavids.commons.test.NonPublicServiceInterface1 must be a public non-abstract class");

    assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  void setServices_empty() {
    MockServices.setServices(TestableServiceInterface1.class);

    assumeThat(getServiceInterface(ServiceInterface1.class)).isNotNull();

    MockServices.setServices();

    assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  @Test
  void setServices_one() {
    MockServices.setServices(TestableServiceInterface1.class);

    assertTestableServiceInterface1Registration();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  @Test
  void setServices_several() {
    MockServices.setServices(TestableServiceInterface2.class, TestableServiceInterface1.class);

    assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));
    assertIsTestableServiceInterface2(getServiceInterface(ServiceInterface2.class));
    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  @Test
  void setServices_registered() {
    assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  @Test
  void setServices_registed_last() {
    MockServices.setServices(TestableServiceInterface3.class);

    Iterator<ServiceInterface3> providers = load(ServiceInterface3.class).iterator();

    assertIsTestableServiceInterface3(providers.next());
    assertIsRegisteredServiceInterface3(providers.next());

    assertThat(providers.hasNext()).isFalse();
  }

  @SuppressWarnings("PMD.AvoidThreadGroup")
  @Test
  void setServices_accessible_by_all_threads() throws InterruptedException {
    ThreadGroup parentThreadGroup = Thread.currentThread().getThreadGroup().getParent();

    assumeThat(parentThreadGroup).isNotNull();

    MockServices.setServices(TestableServiceInterface1.class);

    ServiceInterface1 serviceInterface1 = getServiceInterface(ServiceInterface1.class);
    ServiceInterface1[] serviceInterface1InThread = new ServiceInterface1[2];

    ServiceInterface2 serviceInterface2 = getServiceInterface(ServiceInterface2.class);
    ServiceInterface2[] serviceInterface2InThread = new ServiceInterface2[2];

    Thread threadSameThreadGroup =
        new Thread(
            () -> {
              serviceInterface1InThread[0] = getServiceInterface(ServiceInterface1.class);
              serviceInterface2InThread[0] = getServiceInterface(ServiceInterface2.class);
            });
    threadSameThreadGroup.start();
    SECONDS.timedJoin(threadSameThreadGroup, TIMEOUT);

    Thread threadParentThreadGroup =
        new Thread(
            parentThreadGroup,
            () -> {
              serviceInterface1InThread[1] = getServiceInterface(ServiceInterface1.class);
              serviceInterface2InThread[1] = getServiceInterface(ServiceInterface2.class);
            });
    threadParentThreadGroup.start();
    SECONDS.timedJoin(threadParentThreadGroup, TIMEOUT);

    assertIsTestableServiceInterface1(serviceInterface1);
    assertIsTestableServiceInterface1(serviceInterface1InThread[0]);
    assertIsTestableServiceInterface1(serviceInterface1InThread[1]);

    assertThat(serviceInterface2).isNull();
    assertThat(serviceInterface2InThread[0]).isNull();
    assertThat(serviceInterface2InThread[1]).isNull();

    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  @SuppressWarnings("PMD.AvoidThreadGroup")
  @Test
  void setServices_multiple_threads()
      throws BrokenBarrierException, InterruptedException, TimeoutException {

    ThreadGroup parentThreadGroup = Thread.currentThread().getThreadGroup().getParent();

    assumeThat(parentThreadGroup).isNotNull();

    ExecutorService executorService = Executors.newCachedThreadPool();

    CyclicBarrier allStartedGate = new CyclicBarrier(5);
    CyclicBarrier servicesSetGate = new CyclicBarrier(5);

    Thread delayedGet =
        new Thread(
            () ->
                assertTestableServiceInterface1RegistrationRunnable(
                    allStartedGate, servicesSetGate));

    Thread daemonGet =
        new Thread(
            () ->
                assertTestableServiceInterface1RegistrationRunnable(
                    allStartedGate, servicesSetGate));
    daemonGet.setDaemon(true);

    Thread delayedInParentGroupGet =
        new Thread(
            parentThreadGroup,
            () ->
                assertTestableServiceInterface1RegistrationRunnable(
                    allStartedGate, servicesSetGate));

    Thread daemonInParentGet =
        new Thread(
            parentThreadGroup,
            () ->
                assertTestableServiceInterface1RegistrationRunnable(
                    allStartedGate, servicesSetGate));
    daemonInParentGet.setDaemon(true);

    delayedGet.start();
    daemonGet.start();
    delayedInParentGroupGet.start();
    daemonInParentGet.start();

    allStartedGate.await(TIMEOUT, SECONDS);

    MockServices.setServices(TestableServiceInterface1.class);

    servicesSetGate.await(TIMEOUT, SECONDS);

    assertTestableServiceInterface1Registration();

    CompletableFuture.runAsync(MockServicesTest::assertTestableServiceInterface1Registration)
        .join();

    CompletableFuture.runAsync(
            MockServicesTest::assertTestableServiceInterface1Registration, executorService)
        .join();

    SECONDS.timedJoin(delayedGet, TIMEOUT);
    SECONDS.timedJoin(daemonGet, TIMEOUT);
    SECONDS.timedJoin(delayedInParentGroupGet, TIMEOUT);
    SECONDS.timedJoin(daemonInParentGet, TIMEOUT);

    assertTestableServiceInterface1Registration();

    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(TIMEOUT, SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
    }
  }

  @Test
  void setServices_thread_setContextClassLoader_throws_SecurityException()
      throws InterruptedException, BrokenBarrierException, TimeoutException {

    CyclicBarrier gate = new CyclicBarrier(3);

    AtomicBoolean called = new AtomicBoolean(false);

    Thread exceptionThrowingThread =
        new Thread(
            () -> {
              try {
                gate.await(TIMEOUT, SECONDS);
                TimeUnit.MILLISECONDS.sleep(10L);
              } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(e);
              }
            }) {

          @Override
          public void setContextClassLoader(ClassLoader cl) {
            called.compareAndSet(false, true);
            throw new SecurityException();
          }
        };

    Thread setServicesThread =
        new Thread(
            () -> {
              try {
                gate.await(TIMEOUT, SECONDS);
                MockServices.setServices(TestableServiceInterface1.class);
              } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(e);
              }
            });

    assumeThat(getServiceInterface(ServiceInterface1.class)).isNull();

    exceptionThrowingThread.start();
    setServicesThread.start();

    gate.await(TIMEOUT, SECONDS);

    SECONDS.timedJoin(exceptionThrowingThread, TIMEOUT);
    SECONDS.timedJoin(setServicesThread, TIMEOUT);

    assertThat(called).isTrue();

    assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));

    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  void contextClassLoader_getResource_found() throws IOException {
    MockServices.setServices(TestableServiceInterface1.class);

    String serviceInterface1Name = ServiceInterface1.class.getName();

    URL resource =
        Thread.currentThread()
            .getContextClassLoader()
            .getResource("META-INF/services/" + serviceInterface1Name);

    assertThat(resource).isNotNull();
    assertThat(resource.toExternalForm()).endsWith(serviceInterface1Name);

    URLConnection urlConnection = resource.openConnection();

    urlConnection.connect();

    try (InputStream inputStream = urlConnection.getInputStream()) {
      assertThat(inputStream.available()).isGreaterThan(0);
    }
  }

  @Test
  void contextClassLoader_getResource_not_found() {
    MockServices.setServices(TestableServiceInterface1.class);

    assertThat(Thread.currentThread().getContextClassLoader().getResource("META-INF/MANIFEST2.MF"))
        .isNull();
  }

  @Test
  void contextClassLoader_getResources_service_not_found() {
    MockServices.setServices(TestableServiceInterface1.class);

    assertThat(Thread.currentThread().getContextClassLoader().getResource("META-INF/services/x"))
        .isNull();
  }

  private static final long TIMEOUT = 10L;

  private static <T> T getServiceInterface(Class<T> clazz) {
    Iterator<T> providers = load(clazz).iterator();

    return providers.hasNext() ? providers.next() : null;
  }

  private static void assertIsTestableServiceInterface1(ServiceInterface1 serviceInterface1) {
    assertThat(serviceInterface1).isNotNull();
    assertThat(serviceInterface1).isInstanceOf(TestableServiceInterface1.class);
    assertThat(serviceInterface1.value()).isEqualTo(1);
  }

  private static void assertIsTestableServiceInterface2(ServiceInterface2 serviceInterface2) {
    assertThat(serviceInterface2).isNotNull();
    assertThat(serviceInterface2).isInstanceOf(ServiceInterface2.class);
    assertThat(serviceInterface2.value()).isEqualTo(2);
  }

  private static void assertIsTestableServiceInterface3(ServiceInterface3 serviceInterface3) {
    assertThat(serviceInterface3).isNotNull();
    assertThat(serviceInterface3).isInstanceOf(TestableServiceInterface3.class);
    assertThat(serviceInterface3.value()).isEqualTo(3);
  }

  private static void assertIsRegisteredServiceInterface3(ServiceInterface3 serviceInterface3) {
    assertThat(serviceInterface3).isNotNull();
    assertThat(serviceInterface3).isInstanceOf(RegisteredServiceInterface3.class);
    assertThat(serviceInterface3.value()).isEqualTo(333);
  }

  private static void assertTestableServiceInterface1Registration() {
    assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  private static void assertTestableServiceInterface1RegistrationRunnable(
      CyclicBarrier allStartedGate, CyclicBarrier servicesSetGate) {

    try {
      allStartedGate.await(TIMEOUT, SECONDS);
      servicesSetGate.await(TIMEOUT, SECONDS);

      assertTestableServiceInterface1Registration();
    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }
}
