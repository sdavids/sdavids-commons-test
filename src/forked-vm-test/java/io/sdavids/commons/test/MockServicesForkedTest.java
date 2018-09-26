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

import static java.lang.Thread.currentThread;
import static java.util.ServiceLoader.load;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@SuppressWarnings("ClassCanBeStatic")
class MockServicesForkedTest {

  @AfterEach
  void tearDown() {
    MockServices.setServices();
  }

  @Test
  void withServicesNull() {
    assertThrows(
        NullPointerException.class, () -> MockServices.setServices((Class<?>[]) null), "services");

    assertNoMockServiceRegistrations(currentThread());
  }

  @Test
  void withClassHavingNonPublicNoArgCtor() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MockServices.setServices(NonPublicNoArgCtorServiceInterface1.class),
        "Class io.sdavids.commons.test.NonPublicNoArgCtorServiceInterface1 has no public no-arg constructor");

    assertNoMockServiceRegistrations(currentThread());
  }

  @Test
  void withAbstractClass() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MockServices.setServices(AbstractServiceInterface1.class),
        "Class io.sdavids.commons.test.AbstractServiceInterface1 must be a public non-abstract class");

    assertNoMockServiceRegistrations(currentThread());
  }

  @Test
  void withNonPublicClass() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MockServices.setServices(NonPublicServiceInterface1.class),
        "Class io.sdavids.commons.test.NonPublicServiceInterface1 must be a public non-abstract class");

    assertNoMockServiceRegistrations(currentThread());
  }

  @Test
  void clearsServices() {
    MockServices.setServices(TestableServiceInterface1.class);

    Thread thread = currentThread();

    assumeThat(getServiceInterface(thread, ServiceInterface1.class)).isNotNull();

    MockServices.setServices();

    assertNoMockServiceRegistrations(thread);
  }

  @Test
  void returnsOne() {
    MockServices.setServices(TestableServiceInterface1.class);

    Thread thread = currentThread();

    assertTestableServiceInterface1Registration(thread);
    assertThat(getServiceInterface(thread, ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(
        thread, getServiceInterface(thread, ServiceInterface3.class));
  }

  @Test
  void returnsSeveral() {
    MockServices.setServices(TestableServiceInterface2.class, TestableServiceInterface1.class);

    Thread thread = currentThread();

    assertIsTestableServiceInterface1(thread, getServiceInterface(thread, ServiceInterface1.class));
    assertIsTestableServiceInterface2(thread, getServiceInterface(thread, ServiceInterface2.class));
    assertIsRegisteredServiceInterface3(
        thread, getServiceInterface(thread, ServiceInterface3.class));
  }

  @Test
  void returnsRegistered() {
    assertNoMockServiceRegistrations(currentThread());
  }

  @Test
  void returnsRegistedLast() {
    MockServices.setServices(TestableServiceInterface3.class);

    Thread thread = currentThread();

    Iterator<ServiceInterface3> providers = getServiceProviders(thread, ServiceInterface3.class);

    assertIsTestableServiceInterface3(thread, providers.next());
    assertIsRegisteredServiceInterface3(thread, providers.next());

    assertThat(providers.hasNext()).isFalse();
  }

  @SuppressWarnings("PMD.AvoidThreadGroup")
  @Test
  void accessibleByAllThreads(TestInfo testInfo) throws InterruptedException {
    Thread thread = currentThread();
    ThreadGroup parentThreadGroup = thread.getThreadGroup().getParent();

    assumeThat(parentThreadGroup).isNotNull();

    MockServices.setServices(TestableServiceInterface1.class);

    ServiceInterface1 serviceInterface1 = getServiceInterface(thread, ServiceInterface1.class);
    ServiceInterface1[] serviceInterface1InThread = new ServiceInterface1[2];

    ServiceInterface2 serviceInterface2 = getServiceInterface(thread, ServiceInterface2.class);
    ServiceInterface2[] serviceInterface2InThread = new ServiceInterface2[2];

    Thread threadSameThreadGroup =
        new Thread(
            () -> {
              serviceInterface1InThread[0] = getServiceInterface(thread, ServiceInterface1.class);
              serviceInterface2InThread[0] = getServiceInterface(thread, ServiceInterface2.class);
            },
            "threadSameThreadGroup-" + testInfo.getDisplayName());
    threadSameThreadGroup.start();
    SECONDS.timedJoin(threadSameThreadGroup, TIMEOUT);

    Thread threadParentThreadGroup =
        new Thread(
            parentThreadGroup,
            () -> {
              serviceInterface1InThread[1] = getServiceInterface(thread, ServiceInterface1.class);
              serviceInterface2InThread[1] = getServiceInterface(thread, ServiceInterface2.class);
            },
            "threadParentThreadGroup-" + testInfo.getDisplayName());
    threadParentThreadGroup.start();
    SECONDS.timedJoin(threadParentThreadGroup, TIMEOUT);

    assertIsTestableServiceInterface1(thread, serviceInterface1);
    assertIsTestableServiceInterface1(thread, serviceInterface1InThread[0]);
    assertIsTestableServiceInterface1(thread, serviceInterface1InThread[1]);

    assertThat(serviceInterface2).isNull();
    assertThat(serviceInterface2InThread[0]).isNull();
    assertThat(serviceInterface2InThread[1]).isNull();

    assertIsRegisteredServiceInterface3(
        thread, getServiceInterface(thread, ServiceInterface3.class));
  }

  @SuppressWarnings("PMD.AvoidThreadGroup")
  @Test
  void accessibleByMultipleThreads(TestInfo testInfo)
      throws BrokenBarrierException, InterruptedException, TimeoutException {

    Thread thread = currentThread();
    ThreadGroup parentThreadGroup = thread.getThreadGroup().getParent();

    assumeThat(parentThreadGroup).isNotNull();

    CyclicBarrier allStartedGate = new CyclicBarrier(6);
    CyclicBarrier servicesSetGate = new CyclicBarrier(6);

    Thread delayedGet =
        new Thread(
            () ->
                assertTestableServiceInterface1RegistrationRunnable(
                    currentThread(), allStartedGate, servicesSetGate),
            "delayedGet-" + testInfo.getDisplayName());

    Thread daemonGet =
        new Thread(
            () ->
                assertTestableServiceInterface1RegistrationRunnable(
                    currentThread(), allStartedGate, servicesSetGate),
            "daemonGet-" + testInfo.getDisplayName());
    daemonGet.setDaemon(true);

    Thread delayedInParentGroupGet =
        new Thread(
            parentThreadGroup,
            () ->
                assertTestableServiceInterface1RegistrationRunnable(
                    currentThread(), allStartedGate, servicesSetGate),
            "delayedInParentGroupGet-" + testInfo.getDisplayName());

    Thread daemonInParentGet =
        new Thread(
            parentThreadGroup,
            () ->
                assertTestableServiceInterface1RegistrationRunnable(
                    currentThread(), allStartedGate, servicesSetGate),
            "daemonInParentGet-" + testInfo.getDisplayName());
    daemonInParentGet.setDaemon(true);

    ForkJoinTask<?> forkJoinTask =
        commonPool()
            .submit(
                () ->
                    assertNoMockServiceRegistrationsInCommonPool(
                        currentThread(), allStartedGate, servicesSetGate));

    delayedGet.start();
    daemonGet.start();
    delayedInParentGroupGet.start();
    daemonInParentGet.start();

    allStartedGate.await(TIMEOUT, SECONDS);

    MockServices.setServices(TestableServiceInterface1.class);

    servicesSetGate.await(TIMEOUT, SECONDS);

    assertTestableServiceInterface1Registration(thread);

    SECONDS.timedJoin(delayedGet, TIMEOUT);
    SECONDS.timedJoin(daemonGet, TIMEOUT);
    SECONDS.timedJoin(delayedInParentGroupGet, TIMEOUT);
    SECONDS.timedJoin(daemonInParentGet, TIMEOUT);

    forkJoinTask.quietlyJoin();

    assertTestableServiceInterface1Registration(thread);
  }

  @Test
  void setContextClassLoaderThrowsSecurityException(TestInfo testInfo)
      throws InterruptedException, BrokenBarrierException, TimeoutException {

    CyclicBarrier gate = new CyclicBarrier(3);

    AtomicBoolean called = new AtomicBoolean(false);

    Thread thread = currentThread();

    Thread exceptionThrowingThread =
        new Thread(
            () -> {
              try {
                gate.await(TIMEOUT, SECONDS);
                MILLISECONDS.sleep(10L);
              } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(e);
              }
            },
            "exceptionThrowingThread-" + testInfo.getDisplayName()) {

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
            },
            "setServicesThread-" + testInfo.getDisplayName());

    assumeThat(getServiceInterface(thread, ServiceInterface1.class)).isNull();

    exceptionThrowingThread.start();
    setServicesThread.start();

    gate.await(TIMEOUT, SECONDS);

    SECONDS.timedJoin(exceptionThrowingThread, TIMEOUT);
    SECONDS.timedJoin(setServicesThread, TIMEOUT);

    assertThat(called).isTrue();

    assertIsTestableServiceInterface1(thread, getServiceInterface(thread, ServiceInterface1.class));

    assertThat(getServiceInterface(thread, ServiceInterface2.class)).isNull();
  }

  @Test
  void returnsResource() throws IOException {
    MockServices.setServices(TestableServiceInterface1.class);

    String serviceInterface1Name = ServiceInterface1.class.getName();

    URL resource =
        currentThread()
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
  void returnsNoResource() {
    MockServices.setServices(TestableServiceInterface1.class);

    assertThat(currentThread().getContextClassLoader().getResource("META-INF/MANIFEST2.MF"))
        .isNull();
  }

  @Test
  void returnsNoService() {
    MockServices.setServices(TestableServiceInterface1.class);

    assertThat(currentThread().getContextClassLoader().getResource("META-INF/services/x")).isNull();
  }

  private static final long TIMEOUT = 10L;

  private static <T> T getServiceInterface(Thread thread, Class<T> clazz) {
    Iterator<T> providers = getServiceProviders(thread, clazz);

    return providers.hasNext() ? providers.next() : null;
  }

  private static <T> Iterator<T> getServiceProviders(Thread thread, Class<T> clazz) {
    return load(clazz, thread.getContextClassLoader()).iterator();
  }

  private static void assertNoMockServiceRegistrations(Thread thread) {
    assertThat(getServiceInterface(thread, ServiceInterface1.class))
        .as("Thread: %s", thread)
        .isNull();
    assertThat(getServiceInterface(thread, ServiceInterface2.class))
        .as("Thread: %s", thread)
        .isNull();
    assertIsRegisteredServiceInterface3(
        thread, getServiceInterface(thread, ServiceInterface3.class));
  }

  private static void assertNoMockServiceRegistrationsInCommonPool(
      Thread thread, CyclicBarrier allStartedGate, CyclicBarrier servicesSetGate) {

    try {
      allStartedGate.await(TIMEOUT, SECONDS);
      servicesSetGate.await(TIMEOUT, SECONDS);

      assertNoMockServiceRegistrations(thread);
    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertIsTestableServiceInterface1(
      Thread thread, ServiceInterface1 serviceInterface1) {

    assertThat(serviceInterface1).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface1)
        .as("Thread: %s", thread)
        .isInstanceOf(TestableServiceInterface1.class);
    assertThat(serviceInterface1.value()).as("Thread: %s", thread).isEqualTo(1);
  }

  private static void assertIsTestableServiceInterface2(
      Thread thread, ServiceInterface2 serviceInterface2) {

    assertThat(serviceInterface2).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface2).as("Thread: %s", thread).isInstanceOf(ServiceInterface2.class);
    assertThat(serviceInterface2.value()).as("Thread: %s", thread).isEqualTo(2);
  }

  private static void assertIsTestableServiceInterface3(
      Thread thread, ServiceInterface3 serviceInterface3) {

    assertThat(serviceInterface3).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface3)
        .as("Thread: %s", thread)
        .isInstanceOf(TestableServiceInterface3.class);
    assertThat(serviceInterface3.value()).as("Thread: %s", thread).isEqualTo(3);
  }

  private static void assertIsRegisteredServiceInterface3(
      Thread thread, ServiceInterface3 serviceInterface3) {

    assertThat(serviceInterface3).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface3)
        .as("Thread: %s", thread)
        .isInstanceOf(RegisteredServiceInterface3.class);
    assertThat(serviceInterface3.value()).as("Thread: %s", thread).isEqualTo(333);
  }

  private static void assertTestableServiceInterface1Registration(Thread thread) {
    assertIsTestableServiceInterface1(thread, getServiceInterface(thread, ServiceInterface1.class));
    assertThat(getServiceInterface(thread, ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(
        thread, getServiceInterface(thread, ServiceInterface3.class));
  }

  private static void assertTestableServiceInterface1RegistrationRunnable(
      Thread thread, CyclicBarrier allStartedGate, CyclicBarrier servicesSetGate) {

    try {
      allStartedGate.await(TIMEOUT, SECONDS);
      servicesSetGate.await(TIMEOUT, SECONDS);

      assertTestableServiceInterface1Registration(thread);
    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }
}
