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

import io.sdavids.commons.test.WithThreadExtension.WithThread;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

@SuppressWarnings("ClassCanBeStatic")
class MockServicesTest {

  @AfterEach
  void afterEach() {
    MockServices.setServicesForCurrentThread();
  }

  @ExtendWith(WithThreadExtension.class)
  @Nested
  class SetServicesForThread {

    @Test
    void withThreadNull(@WithThread Thread thread) {
      assertThrows(
          NullPointerException.class, () -> MockServices.setServicesForThread(null), "thread");

      assertNoMockServiceRegistrations(thread);
    }

    @Test
    void withServicesNull(@WithThread Thread thread) {
      assertThrows(
          NullPointerException.class,
          () -> MockServices.setServicesForThread(thread, (Class<?>[]) null),
          "services");

      assertNoMockServiceRegistrations(thread);
    }

    @Test
    void withClassHavingNonPublicNoArgCtor(@WithThread Thread thread) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              MockServices.setServicesForThread(thread, NonPublicNoArgCtorServiceInterface1.class),
          "Class io.sdavids.commons.test.NonPublicNoArgCtorServiceInterface1 has no public no-arg constructor");

      assertNoMockServiceRegistrations(thread);
    }

    @Test
    void withAbstractClass(@WithThread Thread thread) {
      assertThrows(
          IllegalArgumentException.class,
          () -> MockServices.setServicesForThread(thread, AbstractServiceInterface1.class),
          "Class io.sdavids.commons.test.AbstractServiceInterface1 must be a public non-abstract class");

      assertNoMockServiceRegistrations(thread);
    }

    @Test
    void withNonPublicClass(@WithThread Thread thread) {
      assertThrows(
          IllegalArgumentException.class,
          () -> MockServices.setServicesForThread(thread, NonPublicServiceInterface1.class),
          "Class io.sdavids.commons.test.NonPublicServiceInterface1 must be a public non-abstract class");

      assertNoMockServiceRegistrations(thread);
    }

    @Test
    void clearsServices(@WithThread Thread thread) {
      MockServices.setServicesForThread(thread, TestableServiceInterface1.class);

      assumeThat(getServiceInterface(thread, ServiceInterface1.class)).isNotNull();

      MockServices.setServicesForThread(thread);

      assertNoMockServiceRegistrations(thread);
    }

    @Test
    void returnsOne(@WithThread Thread thread) {
      MockServices.setServicesForThread(thread, TestableServiceInterface1.class);

      assertTestableServiceInterface1Registration(thread);
      assertThat(getServiceInterface(thread, ServiceInterface2.class)).isNull();
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));
    }

    @Test
    void returnsSeveral(@WithThread Thread thread) {
      MockServices.setServicesForThread(
          thread, TestableServiceInterface2.class, TestableServiceInterface1.class);

      assertIsTestableServiceInterface1(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));
    }

    @Test
    void returnsRegistered(@WithThread Thread thread) {
      assertNoMockServiceRegistrations(thread);
    }

    @Test
    void returnsRegistedLast(@WithThread Thread thread) {
      MockServices.setServicesForThread(thread, TestableServiceInterface3.class);

      Iterator<ServiceInterface3> providers = getServiceProviders(thread, ServiceInterface3.class);

      assertIsTestableServiceInterface3(thread, providers.next());
      assertIsRegisteredServiceInterface3(thread, providers.next());

      assertThat(providers.hasNext()).isFalse();
    }

    @SuppressWarnings("PMD.AvoidThreadGroup")
    @Test
    void accessibleOnlyByThread(TestInfo testInfo)
        throws BrokenBarrierException, InterruptedException, TimeoutException {

      CyclicBarrier allStartedGate = new CyclicBarrier(7);
      CyclicBarrier servicesSetGate = new CyclicBarrier(7);

      Thread threadWithInherited =
          new Thread(
              () -> {
                Thread inheritedGet =
                    new Thread(
                        () ->
                            assertNoMockServiceRegistrationsRunnable(
                                currentThread(), allStartedGate, servicesSetGate),
                        "inheritedGet-" + testInfo.getDisplayName());
                inheritedGet.start();

                try {
                  while (!currentThread().isInterrupted()) {
                    try {
                      MILLISECONDS.sleep(10L);
                    } catch (InterruptedException e) {
                      // allow threadWithInherited to exit
                    }
                  }
                } finally {
                  inheritedGet.interrupt();
                }
              },
              "threadWithInherited-" + testInfo.getDisplayName());
      threadWithInherited.start();

      try {
        ThreadGroup parentThreadGroup = threadWithInherited.getThreadGroup().getParent();

        assumeThat(parentThreadGroup).isNotNull();

        Thread delayedGet =
            new Thread(
                () ->
                    assertNoMockServiceRegistrationsRunnable(
                        currentThread(), allStartedGate, servicesSetGate),
                "delayedGet-" + testInfo.getDisplayName());

        Thread daemonGet =
            new Thread(
                () ->
                    assertNoMockServiceRegistrationsRunnable(
                        currentThread(), allStartedGate, servicesSetGate),
                "daemonGet-" + testInfo.getDisplayName());
        daemonGet.setDaemon(true);

        Thread delayedInParentGroupGet =
            new Thread(
                parentThreadGroup,
                () ->
                    assertNoMockServiceRegistrationsRunnable(
                        currentThread(), allStartedGate, servicesSetGate),
                "delayedInParentGroupGet-" + testInfo.getDisplayName());

        Thread daemonInParentGet =
            new Thread(
                parentThreadGroup,
                () ->
                    assertNoMockServiceRegistrationsRunnable(
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

        assertNoMockServiceRegistrations(threadWithInherited);

        MockServices.setServicesForThread(threadWithInherited, TestableServiceInterface1.class);

        servicesSetGate.await(TIMEOUT, SECONDS);

        assertTestableServiceInterface1Registration(threadWithInherited);
        assertNoMockServiceRegistrations(currentThread());

        SECONDS.timedJoin(delayedGet, TIMEOUT);
        SECONDS.timedJoin(daemonGet, TIMEOUT);
        SECONDS.timedJoin(delayedInParentGroupGet, TIMEOUT);
        SECONDS.timedJoin(daemonInParentGet, TIMEOUT);

        forkJoinTask.quietlyJoin();
      } finally {
        threadWithInherited.interrupt();
      }
    }

    @Test
    void setContextClassLoaderThrowsSecurityException(TestInfo testInfo) {
      AtomicBoolean called = new AtomicBoolean(false);

      Thread exceptionThrowingThread =
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
              testInfo.getDisplayName()) {

            @Override
            public void setContextClassLoader(ClassLoader cl) {
              called.compareAndSet(false, true);
              throw new SecurityException();
            }
          };
      exceptionThrowingThread.start();

      try {
        MockServices.setServicesForThread(exceptionThrowingThread, TestableServiceInterface1.class);

        assertThat(called).isTrue();

        assertNoMockServiceRegistrations(exceptionThrowingThread);
        assertNoMockServiceRegistrations(currentThread());
      } finally {
        exceptionThrowingThread.interrupt();
      }
    }

    @Test
    void returnsResource(@WithThread Thread thread) throws IOException {
      MockServices.setServicesForThread(thread, TestableServiceInterface1.class);

      String serviceInterface1Name = ServiceInterface1.class.getName();

      URL resource =
          thread.getContextClassLoader().getResource("META-INF/services/" + serviceInterface1Name);

      assertThat(resource).isNotNull();
      assertThat(resource.toExternalForm()).endsWith(serviceInterface1Name);

      URLConnection urlConnection = resource.openConnection();

      urlConnection.connect();

      try (InputStream inputStream = urlConnection.getInputStream()) {
        assertThat(inputStream.available()).isGreaterThan(0);
      }
    }

    @Test
    void returnsNoResource(@WithThread Thread thread) {
      MockServices.setServicesForThread(thread, TestableServiceInterface1.class);

      assertThat(thread.getContextClassLoader().getResource("META-INF/MANIFEST2.MF")).isNull();
    }

    @Test
    void returnsNoService(@WithThread Thread thread) {
      MockServices.setServicesForThread(thread, TestableServiceInterface1.class);

      assertThat(thread.getContextClassLoader().getResource("META-INF/services/x")).isNull();
    }
  }

  @Nested
  class SetServicesForCurrentThread {

    @Test
    void withServicesNull() {
      assertThrows(
          NullPointerException.class,
          () -> MockServices.setServicesForCurrentThread((Class<?>[]) null),
          "services");

      assertNoMockServiceRegistrations(currentThread());
    }

    @Test
    void withClassHavingNonPublicNoArgCtor() {
      assertThrows(
          IllegalArgumentException.class,
          () -> MockServices.setServicesForCurrentThread(NonPublicNoArgCtorServiceInterface1.class),
          "Class io.sdavids.commons.test.NonPublicNoArgCtorServiceInterface1 has no public no-arg constructor");

      assertNoMockServiceRegistrations(currentThread());
    }

    @Test
    void withAbstractClass() {
      assertThrows(
          IllegalArgumentException.class,
          () -> MockServices.setServicesForCurrentThread(AbstractServiceInterface1.class),
          "Class io.sdavids.commons.test.AbstractServiceInterface1 must be a public non-abstract class");

      assertNoMockServiceRegistrations(currentThread());
    }

    @Test
    void withNonPublicClass() {
      assertThrows(
          IllegalArgumentException.class,
          () -> MockServices.setServicesForCurrentThread(NonPublicServiceInterface1.class),
          "Class io.sdavids.commons.test.NonPublicServiceInterface1 must be a public non-abstract class");

      assertNoMockServiceRegistrations(currentThread());
    }

    @Test
    void clearsServices() {
      MockServices.setServicesForThread(currentThread(), TestableServiceInterface1.class);

      Thread thread = currentThread();

      assumeThat(getServiceInterface(thread, ServiceInterface1.class)).isNotNull();

      MockServices.setServicesForCurrentThread();

      assertNoMockServiceRegistrations(thread);
    }

    @Test
    void returnsOne() {
      MockServices.setServicesForCurrentThread(TestableServiceInterface1.class);

      Thread thread = currentThread();

      assertTestableServiceInterface1Registration(thread);
      assertThat(getServiceInterface(thread, ServiceInterface2.class)).isNull();
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));
    }

    @Test
    void returnsSeveral() {
      MockServices.setServicesForCurrentThread(
          TestableServiceInterface2.class, TestableServiceInterface1.class);

      Thread thread = currentThread();

      assertIsTestableServiceInterface1(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));
    }

    @Test
    void returnsRegistered() {
      assertNoMockServiceRegistrations(currentThread());
    }

    @Test
    void returnsRegistedLast() {
      MockServices.setServicesForCurrentThread(TestableServiceInterface3.class);

      Thread thread = currentThread();

      Iterator<ServiceInterface3> providers = getServiceProviders(thread, ServiceInterface3.class);

      assertIsTestableServiceInterface3(thread, providers.next());
      assertIsRegisteredServiceInterface3(thread, providers.next());

      assertThat(providers.hasNext()).isFalse();
    }

    @SuppressWarnings("PMD.AvoidThreadGroup")
    @Test
    void accessibleOnlyByCurrentThread(TestInfo testInfo)
        throws BrokenBarrierException, InterruptedException, TimeoutException {

      CyclicBarrier allStartedGate = new CyclicBarrier(6);
      CyclicBarrier servicesSetGate = new CyclicBarrier(6);

      ThreadGroup parentThreadGroup = currentThread().getThreadGroup().getParent();

      assumeThat(parentThreadGroup).isNotNull();

      Thread delayedGet =
          new Thread(
              () ->
                  assertNoMockServiceRegistrationsRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "delayedGet-" + testInfo.getDisplayName());

      Thread daemonGet =
          new Thread(
              () ->
                  assertNoMockServiceRegistrationsRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "daemonGet-" + testInfo.getDisplayName());
      daemonGet.setDaemon(true);

      Thread delayedInParentGroupGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertNoMockServiceRegistrationsRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "delayedInParentGroupGet-" + testInfo.getDisplayName());

      Thread daemonInParentGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertNoMockServiceRegistrationsRunnable(
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

      assertNoMockServiceRegistrations(currentThread());

      MockServices.setServicesForCurrentThread(TestableServiceInterface1.class);

      servicesSetGate.await(TIMEOUT, SECONDS);

      assertTestableServiceInterface1Registration(currentThread());

      SECONDS.timedJoin(delayedGet, TIMEOUT);
      SECONDS.timedJoin(daemonGet, TIMEOUT);
      SECONDS.timedJoin(delayedInParentGroupGet, TIMEOUT);
      SECONDS.timedJoin(daemonInParentGet, TIMEOUT);

      forkJoinTask.quietlyJoin();
    }

    @Test
    void setContextClassLoaderThrowsSecurityException(TestInfo testInfo)
        throws InterruptedException {

      AtomicBoolean called = new AtomicBoolean(false);

      Thread exceptionThrowingThread =
          new Thread(
              () -> MockServices.setServicesForCurrentThread(TestableServiceInterface1.class),
              testInfo.getDisplayName()) {

            @Override
            public void setContextClassLoader(ClassLoader cl) {
              called.compareAndSet(false, true);
              throw new SecurityException();
            }
          };
      exceptionThrowingThread.start();

      SECONDS.timedJoin(exceptionThrowingThread, TIMEOUT);

      assertThat(called).isTrue();

      assertNoMockServiceRegistrations(exceptionThrowingThread);
      assertNoMockServiceRegistrations(currentThread());
    }

    @Test
    void returnsResource() throws IOException {
      MockServices.setServicesForCurrentThread(TestableServiceInterface1.class);

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
      MockServices.setServicesForCurrentThread(TestableServiceInterface1.class);

      assertThat(currentThread().getContextClassLoader().getResource("META-INF/MANIFEST2.MF"))
          .isNull();
    }

    @Test
    void returnsNoService() {
      MockServices.setServicesForCurrentThread(TestableServiceInterface1.class);

      assertThat(currentThread().getContextClassLoader().getResource("META-INF/services/x"))
          .isNull();
    }
  }

  @Nested
  class WithServicesForRunnableInCurrentThread {

    @Test
    void withRunnableNull() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      assertThrows(
          NullPointerException.class,
          () ->
              MockServices.withServicesForRunnableInCurrentThread(
                  null, TestableServiceInterface1.class),
          "runnable");

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void withServicesNull() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      assertThrows(
          NullPointerException.class,
          () -> MockServices.withServicesForRunnableInCurrentThread(() -> {}, (Class<?>[]) null),
          "services");

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void clearsServices() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertThat(getServiceInterface(threadWithin, ServiceInterface1.class)).isNull();
            assertThat(getServiceInterface(threadWithin, ServiceInterface2.class)).isNull();
          });

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void returnsOne() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertIsTestableServiceInterface1(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface1.class));
            assertThat(getServiceInterface(threadWithin, ServiceInterface2.class)).isNull();
          },
          TestableServiceInterface1.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void returnsSeveral() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertIsTestableServiceInterface1(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface1.class));
            assertIsTestableServiceInterface2(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface2.class));
          },
          TestableServiceInterface1.class,
          TestableServiceInterface2.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void returnsRegistered() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertIsRegisteredServiceInterface3(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface3.class));
          });

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));
    }

    @Test
    void returnsRegisteredOverwritingExisting() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread,
          TestableServiceInterface1Negative.class,
          TestableServiceInterface2Negative.class,
          TestableServiceInterface3Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertRegisteredService3Interfaces(thread);

      MockServices.withServicesForRunnableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertIsRegisteredServiceInterface3(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface3.class));
          });

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertRegisteredService3Interfaces(thread);
    }

    @Test
    void returnsRegistedLast() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> assertNewServiceInterface3Registrations(currentThread()),
          TestableServiceInterface3.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));
    }

    @Test
    void returnsRegistedLastOverwritinigExisting() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread,
          TestableServiceInterface1Negative.class,
          TestableServiceInterface2Negative.class,
          TestableServiceInterface3Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertRegisteredService3Interfaces(thread);

      MockServices.withServicesForRunnableInCurrentThread(
          () -> assertNewServiceInterface3Registrations(currentThread()),
          TestableServiceInterface3.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertRegisteredService3Interfaces(thread);
    }

    @Test
    void setContextClassLoaderThrowsSecurityException(TestInfo testInfo)
        throws InterruptedException {

      Thread thread = currentThread();

      MockServices.setServicesForThread(thread, TestableServiceInterface1Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));

      AtomicBoolean called = new AtomicBoolean(false);

      Thread setServicesThread =
          new Thread(
              () ->
                  MockServices.withServicesForRunnableInCurrentThread(
                      () -> {
                        Thread threadWithin = currentThread();

                        assertIsTestableServiceInterface1Negative(
                            threadWithin,
                            getServiceInterface(threadWithin, ServiceInterface1.class));
                      },
                      TestableServiceInterface1.class),
              "setServicesThread-" + testInfo.getDisplayName()) {

            @Override
            public void setContextClassLoader(ClassLoader cl) {
              called.compareAndSet(false, true);
              throw new SecurityException();
            }
          };

      setServicesThread.start();

      SECONDS.timedJoin(setServicesThread, TIMEOUT);

      assertThat(called).isTrue();

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
    }

    @SuppressWarnings("PMD.AvoidThreadGroup")
    @Test
    void accessibleByCurrentThreadAndThreadStartedWithin(TestInfo testInfo)
        throws BrokenBarrierException, InterruptedException, TimeoutException {

      Thread thread = currentThread();

      ThreadGroup parentThreadGroup = thread.getThreadGroup().getParent();

      assumeThat(parentThreadGroup).isNotNull();

      MockServices.setServicesForThread(thread, TestableServiceInterface1Negative.class);

      assertTestableServiceInterface1NegativeRegistration(thread);

      CyclicBarrier allStartedGate = new CyclicBarrier(7);
      CyclicBarrier servicesSetGate = new CyclicBarrier(6);

      Thread delayedGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "delayedGet-" + testInfo.getDisplayName());

      Thread daemonGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "daemonGet-" + testInfo.getDisplayName());
      daemonGet.setDaemon(true);

      Thread delayedInParentGroupGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "delayedInParentGroupGet-" + testInfo.getDisplayName());

      Thread daemonInParentGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "daemonInParentGet-" + testInfo.getDisplayName());
      daemonInParentGet.setDaemon(true);

      ForkJoinTask<?> forkJoinTask =
          commonPool()
              .submit(
                  () ->
                      assertNoMockServiceRegistrationsInCommonPool(
                          currentThread(), allStartedGate, servicesSetGate));

      Thread withServices =
          new Thread(
              () -> {
                Thread threadWithServices = currentThread();

                try {
                  allStartedGate.await(TIMEOUT, SECONDS);

                  assertTestableServiceInterface1NegativeRegistration(threadWithServices);

                  MockServices.withServicesForRunnableInCurrentThread(
                      () -> {
                        try {
                          Thread threadWithin = currentThread();

                          assertTestableServiceInterface1Registration(threadWithin);

                          servicesSetGate.await(TIMEOUT, SECONDS);

                          Thread inheritedGet =
                              new Thread(
                                  () ->
                                      assertTestableServiceInterface1Registration(currentThread()),
                                  "inheritedGet-" + testInfo.getDisplayName());

                          inheritedGet.start();

                          ForkJoinTask<?> forkJoinTaskInside =
                              commonPool()
                                  .submit(
                                      () ->
                                          assertNoMockServiceRegistrationsInCommonPool(
                                              currentThread(), allStartedGate, servicesSetGate));

                          SECONDS.timedJoin(inheritedGet, TIMEOUT);

                          forkJoinTaskInside.quietlyJoin();

                          MILLISECONDS.sleep(50L);

                          assertTestableServiceInterface1Registration(threadWithin);
                        } catch (InterruptedException
                            | BrokenBarrierException
                            | TimeoutException e) {
                          throw new IllegalStateException(e);
                        }
                      },
                      TestableServiceInterface1.class);

                  assertTestableServiceInterface1NegativeRegistration(threadWithServices);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                  throw new IllegalStateException(e);
                }
              },
              "withServices-" + testInfo.getDisplayName());

      withServices.start();
      delayedGet.start();
      daemonGet.start();
      delayedInParentGroupGet.start();
      daemonInParentGet.start();

      allStartedGate.await(TIMEOUT, SECONDS);

      SECONDS.timedJoin(delayedGet, TIMEOUT);
      SECONDS.timedJoin(daemonGet, TIMEOUT);
      SECONDS.timedJoin(delayedInParentGroupGet, TIMEOUT);
      SECONDS.timedJoin(daemonInParentGet, TIMEOUT);

      forkJoinTask.quietlyJoin();

      assertTestableServiceInterface1NegativeRegistration(thread);
    }
  }

  @Nested
  class WithServicesForCallableInCurrentThread {

    @Test
    void withCallableNull() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      assertThrows(
          NullPointerException.class,
          () ->
              MockServices.withServicesForCallableInCurrentThread(
                  null, TestableServiceInterface1.class),
          "callable");

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void withServicesNull() {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      assertThrows(
          NullPointerException.class,
          () -> MockServices.withServicesForCallableInCurrentThread(() -> null, (Class<?>[]) null),
          "services");

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void clearsServices() throws Exception {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertThat(getServiceInterface(threadWithin, ServiceInterface1.class)).isNull();
            assertThat(getServiceInterface(threadWithin, ServiceInterface2.class)).isNull();

            return null;
          });

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void returnsOne() throws Exception {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertIsTestableServiceInterface1(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface1.class));
            assertThat(getServiceInterface(threadWithin, ServiceInterface2.class)).isNull();

            return null;
          },
          TestableServiceInterface1.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void returnsSeveral() throws Exception {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertIsTestableServiceInterface1(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface1.class));
            assertIsTestableServiceInterface2(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface2.class));

            return null;
          },
          TestableServiceInterface1.class,
          TestableServiceInterface2.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
    }

    @Test
    void returnsRegistered() throws Exception {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertIsRegisteredServiceInterface3(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface3.class));

            return null;
          });

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));
    }

    @Test
    void returnsRegisteredOverwritingExisting() throws Exception {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread,
          TestableServiceInterface1Negative.class,
          TestableServiceInterface2Negative.class,
          TestableServiceInterface3Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertRegisteredService3Interfaces(thread);

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            Thread threadWithin = currentThread();

            assertIsRegisteredServiceInterface3(
                threadWithin, getServiceInterface(threadWithin, ServiceInterface3.class));

            return null;
          });

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertRegisteredService3Interfaces(thread);
    }

    @Test
    void returnsRegistedLast() throws Exception {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread, TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> assertNewServiceInterface3Registrations(currentThread()),
          TestableServiceInterface3.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(
          thread, getServiceInterface(thread, ServiceInterface3.class));
    }

    @Test
    void returnsRegistedLastOverwritinigExisting() throws Exception {
      Thread thread = currentThread();

      MockServices.setServicesForThread(
          thread,
          TestableServiceInterface1Negative.class,
          TestableServiceInterface2Negative.class,
          TestableServiceInterface3Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertRegisteredService3Interfaces(thread);

      MockServices.withServicesForCallableInCurrentThread(
          () -> assertNewServiceInterface3Registrations(currentThread()),
          TestableServiceInterface3.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(
          thread, getServiceInterface(thread, ServiceInterface2.class));
      assertRegisteredService3Interfaces(thread);
    }

    @Test
    void setContextClassLoaderThrowsSecurityException(TestInfo testInfo)
        throws InterruptedException {

      Thread thread = currentThread();

      MockServices.setServicesForThread(thread, TestableServiceInterface1Negative.class);

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));

      AtomicBoolean called = new AtomicBoolean(false);

      Thread setServicesThread =
          new Thread(
              () -> {
                try {
                  MockServices.withServicesForCallableInCurrentThread(
                      () -> {
                        Thread threadWithin = currentThread();

                        assertIsTestableServiceInterface1Negative(
                            threadWithin,
                            getServiceInterface(threadWithin, ServiceInterface1.class));

                        return null;
                      },
                      TestableServiceInterface1.class);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              "setServicesThread-" + testInfo.getDisplayName()) {

            @Override
            public void setContextClassLoader(ClassLoader cl) {
              called.compareAndSet(false, true);
              throw new SecurityException();
            }
          };

      setServicesThread.start();

      SECONDS.timedJoin(setServicesThread, TIMEOUT);

      assertThat(called).isTrue();

      assertIsTestableServiceInterface1Negative(
          thread, getServiceInterface(thread, ServiceInterface1.class));
    }

    @SuppressWarnings("PMD.AvoidThreadGroup")
    @Test
    void accessibleByCurrentThreadAndThreadStartedWithin(TestInfo testInfo)
        throws BrokenBarrierException, InterruptedException, TimeoutException {

      Thread thread = currentThread();
      ThreadGroup parentThreadGroup = thread.getThreadGroup().getParent();

      assumeThat(parentThreadGroup).isNotNull();

      MockServices.setServicesForThread(thread, TestableServiceInterface1Negative.class);

      assertTestableServiceInterface1NegativeRegistration(thread);

      CyclicBarrier allStartedGate = new CyclicBarrier(7);
      CyclicBarrier servicesSetGate = new CyclicBarrier(6);

      Thread delayedGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "delayedGet-" + testInfo.getDisplayName());

      Thread daemonGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "daemonGet-" + testInfo.getDisplayName());
      daemonGet.setDaemon(true);

      Thread delayedInParentGroupGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "delayedInParentGroupGet-" + testInfo.getDisplayName());

      Thread daemonInParentGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      currentThread(), allStartedGate, servicesSetGate),
              "daemonInParentGet-" + testInfo.getDisplayName());
      daemonInParentGet.setDaemon(true);

      ForkJoinTask<?> forkJoinTask =
          commonPool()
              .submit(
                  () ->
                      assertNoMockServiceRegistrationsInCommonPool(
                          currentThread(), allStartedGate, servicesSetGate));

      Thread withServices =
          new Thread(
              () -> {
                Thread threadWithServices = currentThread();

                try {
                  allStartedGate.await(TIMEOUT, SECONDS);

                  assertTestableServiceInterface1NegativeRegistration(threadWithServices);

                  MockServices.withServicesForCallableInCurrentThread(
                      () -> {
                        Thread threadWithin = currentThread();

                        assertTestableServiceInterface1Registration(threadWithin);

                        servicesSetGate.await(TIMEOUT, SECONDS);

                        Thread inheritedGet =
                            new Thread(
                                () -> assertTestableServiceInterface1Registration(currentThread()),
                                "inheritedGet-" + testInfo.getDisplayName());

                        inheritedGet.start();

                        ForkJoinTask<?> forkJoinTaskInside =
                            commonPool()
                                .submit(
                                    () ->
                                        assertNoMockServiceRegistrationsInCommonPool(
                                            currentThread(), allStartedGate, servicesSetGate));

                        SECONDS.timedJoin(inheritedGet, TIMEOUT);

                        forkJoinTaskInside.quietlyJoin();

                        MILLISECONDS.sleep(50L);

                        assertTestableServiceInterface1Registration(threadWithin);

                        return null;
                      },
                      TestableServiceInterface1.class);

                  assertTestableServiceInterface1NegativeRegistration(threadWithServices);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }

                assertTestableServiceInterface1NegativeRegistration(threadWithServices);
              },
              "withServices-" + testInfo.getDisplayName());

      withServices.start();
      delayedGet.start();
      daemonGet.start();
      delayedInParentGroupGet.start();
      daemonInParentGet.start();

      allStartedGate.await(TIMEOUT, SECONDS);

      SECONDS.timedJoin(delayedGet, TIMEOUT);
      SECONDS.timedJoin(daemonGet, TIMEOUT);
      SECONDS.timedJoin(delayedInParentGroupGet, TIMEOUT);
      SECONDS.timedJoin(daemonInParentGet, TIMEOUT);

      forkJoinTask.quietlyJoin();

      assertTestableServiceInterface1NegativeRegistration(thread);
    }
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

  private static void assertIsTestableServiceInterface1Negative(
      Thread thread, ServiceInterface1 serviceInterface1) {

    assertThat(serviceInterface1).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface1)
        .as("Thread: %s", thread)
        .isInstanceOf(TestableServiceInterface1Negative.class);
    assertThat(serviceInterface1.value()).as("Thread: %s", thread).isEqualTo(-1);
  }

  private static void assertIsTestableServiceInterface2(
      Thread thread, ServiceInterface2 serviceInterface2) {

    assertThat(serviceInterface2).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface2).as("Thread: %s", thread).isInstanceOf(ServiceInterface2.class);
    assertThat(serviceInterface2.value()).as("Thread: %s", thread).isEqualTo(2);
  }

  private static void assertIsTestableServiceInterface2Negative(
      Thread thread, ServiceInterface2 serviceInterface2) {

    assertThat(serviceInterface2).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface2)
        .as("Thread: %s", thread)
        .isInstanceOf(TestableServiceInterface2Negative.class);
    assertThat(serviceInterface2.value()).as("Thread: %s", thread).isEqualTo(-2);
  }

  private static void assertIsTestableServiceInterface3(
      Thread thread, ServiceInterface3 serviceInterface3) {

    assertThat(serviceInterface3).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface3)
        .as("Thread: %s", thread)
        .isInstanceOf(TestableServiceInterface3.class);
    assertThat(serviceInterface3.value()).as("Thread: %s", thread).isEqualTo(3);
  }

  private static void assertIsTestableServiceInterface3Negative(
      Thread thread, ServiceInterface3 serviceInterface3) {

    assertThat(serviceInterface3).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface3)
        .as("Thread: %s", thread)
        .isInstanceOf(TestableServiceInterface3Negative.class);
    assertThat(serviceInterface3.value()).as("Thread: %s", thread).isEqualTo(-3);
  }

  private static void assertIsRegisteredServiceInterface3(
      Thread thread, ServiceInterface3 serviceInterface3) {

    assertThat(serviceInterface3).as("Thread: %s", thread).isNotNull();
    assertThat(serviceInterface3)
        .as("Thread: %s", thread)
        .isInstanceOf(RegisteredServiceInterface3.class);
    assertThat(serviceInterface3.value()).as("Thread: %s", thread).isEqualTo(333);
  }

  private static void assertRegisteredService3Interfaces(Thread thread) {
    Iterator<ServiceInterface3> providers = getServiceProviders(thread, ServiceInterface3.class);

    assertIsTestableServiceInterface3Negative(thread, providers.next());
    assertIsRegisteredServiceInterface3(thread, providers.next());
  }

  private static void assertTestableServiceInterface1Registration(Thread thread) {
    assertIsTestableServiceInterface1(thread, getServiceInterface(thread, ServiceInterface1.class));
    assertThat(getServiceInterface(thread, ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(
        thread, getServiceInterface(thread, ServiceInterface3.class));
  }

  private static void assertTestableServiceInterface1NegativeRegistration(Thread thread) {
    assertIsTestableServiceInterface1Negative(
        thread, getServiceInterface(thread, ServiceInterface1.class));
    assertThat(getServiceInterface(thread, ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(
        thread, getServiceInterface(thread, ServiceInterface3.class));
  }

  private static Object assertNewServiceInterface3Registrations(Thread thread) {
    Iterator<ServiceInterface3> providers = getServiceProviders(thread, ServiceInterface3.class);

    assertIsTestableServiceInterface3(thread, providers.next());
    assertIsRegisteredServiceInterface3(thread, providers.next());

    return null;
  }

  private static void assertTestableServiceInterface1NegativeRegistrationRunnable(
      Thread thread, CyclicBarrier allStartedGate, CyclicBarrier servicesSetGate) {

    try {
      allStartedGate.await(TIMEOUT, SECONDS);
      servicesSetGate.await(TIMEOUT, SECONDS);

      assertTestableServiceInterface1NegativeRegistration(thread);
    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertNoMockServiceRegistrationsRunnable(
      Thread thread, CyclicBarrier allStartedGate, CyclicBarrier servicesSetGate) {

    try {
      allStartedGate.await(TIMEOUT, SECONDS);
      servicesSetGate.await(TIMEOUT, SECONDS);

      assertNoMockServiceRegistrations(thread);
    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }
}
