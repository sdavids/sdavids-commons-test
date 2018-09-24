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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@SuppressWarnings("ClassCanBeStatic")
class MockServicesTest {

  @Nested
  class SetServices {

    @Test
    void withNull() {
      assertThrows(
          NullPointerException.class,
          () -> MockServices.setServices((Class<?>[]) null),
          "services");

      assertNoMockServiceRegistrations();
    }

    @Test
    void withClassHavingNonPublicNoArgCtor() {
      assertThrows(
          IllegalArgumentException.class,
          () -> MockServices.setServices(NonPublicNoArgCtorServiceInterface1.class),
          "Class io.sdavids.commons.test.NonPublicNoArgCtorServiceInterface1 has no public no-arg constructor");

      assertNoMockServiceRegistrations();
    }

    @Test
    void withAbstractClass() {
      assertThrows(
          IllegalArgumentException.class,
          () -> MockServices.setServices(AbstractServiceInterface1.class),
          "Class io.sdavids.commons.test.AbstractServiceInterface1 must be a public non-abstract class");

      assertNoMockServiceRegistrations();
    }

    @Test
    void withNonPublicClass() {
      assertThrows(
          IllegalArgumentException.class,
          () -> MockServices.setServices(NonPublicServiceInterface1.class),
          "Class io.sdavids.commons.test.NonPublicServiceInterface1 must be a public non-abstract class");

      assertNoMockServiceRegistrations();
    }

    @Test
    void clearsServices() {
      MockServices.setServices(TestableServiceInterface1.class);

      assumeThat(getServiceInterface(ServiceInterface1.class)).isNotNull();

      MockServices.setServices();

      assertNoMockServiceRegistrations();
    }

    @Test
    void returnsOne() {
      MockServices.setServices(TestableServiceInterface1.class);

      assertTestableServiceInterface1Registration();
      assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
    }

    @Test
    void returnsSeveral() {
      MockServices.setServices(TestableServiceInterface2.class, TestableServiceInterface1.class);

      assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
    }

    @Test
    void returnsRegistered() {
      assertNoMockServiceRegistrations();
    }

    @Test
    void returnsRegistedLast() {
      MockServices.setServices(TestableServiceInterface3.class);

      Iterator<ServiceInterface3> providers = load(ServiceInterface3.class).iterator();

      assertIsTestableServiceInterface3(providers.next());
      assertIsRegisteredServiceInterface3(providers.next());

      assertThat(providers.hasNext()).isFalse();
    }

    @SuppressWarnings("PMD.AvoidThreadGroup")
    @Test
    void accessibleByAllThreads() throws InterruptedException {
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
              },
              "threadSameThreadGroup-accessibleByAllThreads");
      threadSameThreadGroup.start();
      SECONDS.timedJoin(threadSameThreadGroup, TIMEOUT);

      Thread threadParentThreadGroup =
          new Thread(
              parentThreadGroup,
              () -> {
                serviceInterface1InThread[1] = getServiceInterface(ServiceInterface1.class);
                serviceInterface2InThread[1] = getServiceInterface(ServiceInterface2.class);
              },
              "threadParentThreadGroup-accessibleByAllThreads");
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
    void accessibleByMultipleThreads()
        throws BrokenBarrierException, InterruptedException, TimeoutException {

      ThreadGroup parentThreadGroup = Thread.currentThread().getThreadGroup().getParent();

      assumeThat(parentThreadGroup).isNotNull();

      CyclicBarrier allStartedGate = new CyclicBarrier(6);
      CyclicBarrier servicesSetGate = new CyclicBarrier(6);

      Thread delayedGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1RegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "delayedGet-accessibleByMultipleThreads");

      Thread daemonGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1RegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "daemonGet-accessibleByMultipleThreads");
      daemonGet.setDaemon(true);

      Thread delayedInParentGroupGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1RegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "delayedInParentGroupGet-accessibleByMultipleThreads");

      Thread daemonInParentGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1RegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "daemonInParentGet-accessibleByMultipleThreads");
      daemonInParentGet.setDaemon(true);

      ForkJoinTask<?> forkJoinTask =
          commonPool()
              .submit(
                  () ->
                      assertNoMockServiceRegistrationsInCommonPool(
                          allStartedGate, servicesSetGate));

      delayedGet.start();
      daemonGet.start();
      delayedInParentGroupGet.start();
      daemonInParentGet.start();

      allStartedGate.await(TIMEOUT, SECONDS);

      MockServices.setServices(TestableServiceInterface1.class);

      servicesSetGate.await(TIMEOUT, SECONDS);

      assertTestableServiceInterface1Registration();

      SECONDS.timedJoin(delayedGet, TIMEOUT);
      SECONDS.timedJoin(daemonGet, TIMEOUT);
      SECONDS.timedJoin(delayedInParentGroupGet, TIMEOUT);
      SECONDS.timedJoin(daemonInParentGet, TIMEOUT);

      forkJoinTask.quietlyJoin();

      assertTestableServiceInterface1Registration();
    }

    @Test
    void setContextClassLoaderThrowsSecurityException()
        throws InterruptedException, BrokenBarrierException, TimeoutException {

      CyclicBarrier gate = new CyclicBarrier(3);

      AtomicBoolean called = new AtomicBoolean(false);

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
              "exceptionThrowingThread-setContextClassLoaderThrowsSecurityException") {

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
              "setServicesThread-setContextClassLoaderThrowsSecurityException");

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
    void returnsResource() throws IOException {
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
    void returnsNoResource() {
      MockServices.setServices(TestableServiceInterface1.class);

      assertThat(
              Thread.currentThread().getContextClassLoader().getResource("META-INF/MANIFEST2.MF"))
          .isNull();
    }

    @Test
    void returnsNoService() {
      MockServices.setServices(TestableServiceInterface1.class);

      assertThat(Thread.currentThread().getContextClassLoader().getResource("META-INF/services/x"))
          .isNull();
    }
  }

  @Nested
  class WithServicesForRunnableInCurrentThread {

    @Test
    void withRunnableNull() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));

      assertThrows(
          NullPointerException.class,
          () ->
              MockServices.withServicesForRunnableInCurrentThread(
                  null, TestableServiceInterface1.class),
          "runnable");

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
    }

    @Test
    void clearsServices() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> {
            assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
            assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
          });

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
    }

    @Test
    void returnsOne() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> {
            assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));
            assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
          },
          TestableServiceInterface1.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
    }

    @Test
    void returnsSeveral() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> {
            assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));
            assertIsTestableServiceInterface2(getServiceInterface(ServiceInterface2.class));
          },
          TestableServiceInterface1.class,
          TestableServiceInterface2.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
    }

    @Test
    void returnsRegistered() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));

      MockServices.withServicesForRunnableInCurrentThread(
          () -> assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class)));

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
    }

    @Test
    void returnsRegisteredOverwritingExisting() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class,
          TestableServiceInterface2Negative.class,
          TestableServiceInterface3Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertRegisteredService3Interfaces();

      MockServices.withServicesForRunnableInCurrentThread(
          () -> assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class)));

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertRegisteredService3Interfaces();
    }

    @Test
    void returnsRegistedLast() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));

      MockServices.withServicesForRunnableInCurrentThread(
          MockServicesTest::assertNewServiceInterface3Registrations,
          TestableServiceInterface3.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
    }

    @Test
    void returnsRegistedLastOverwritinigExisting() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class,
          TestableServiceInterface2Negative.class,
          TestableServiceInterface3Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertRegisteredService3Interfaces();

      MockServices.withServicesForRunnableInCurrentThread(
          MockServicesTest::assertNewServiceInterface3Registrations,
          TestableServiceInterface3.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertRegisteredService3Interfaces();
    }

    @Test
    void setContextClassLoaderThrowsSecurityException() throws InterruptedException {

      MockServices.setServices(TestableServiceInterface1Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));

      AtomicBoolean called = new AtomicBoolean(false);

      Thread setServicesThread =
          new Thread(
              () ->
                  MockServices.withServicesForRunnableInCurrentThread(
                      () ->
                          assertIsTestableServiceInterface1Negative(
                              getServiceInterface(ServiceInterface1.class)),
                      TestableServiceInterface1.class),
              "setServicesThread-withServicesForRunnableInCurrentThread_runnable_thread_setContextClassLoader_throws_SecurityException") {

            @Override
            public void setContextClassLoader(ClassLoader cl) {
              called.compareAndSet(false, true);
              throw new SecurityException();
            }
          };

      setServicesThread.start();

      SECONDS.timedJoin(setServicesThread, TIMEOUT);

      assertThat(called).isTrue();

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
    }

    @SuppressWarnings("PMD.AvoidThreadGroup")
    @Test
    void accessibleByCurrentThreadAndThreadStartedWithin()
        throws BrokenBarrierException, InterruptedException, TimeoutException {

      ThreadGroup parentThreadGroup = Thread.currentThread().getThreadGroup().getParent();

      assumeThat(parentThreadGroup).isNotNull();

      MockServices.setServices(TestableServiceInterface1Negative.class);

      assertTestableServiceInterface1NegativeRegistration();

      CyclicBarrier allStartedGate = new CyclicBarrier(7);
      CyclicBarrier servicesSetGate = new CyclicBarrier(6);

      Thread delayedGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "delayedGet-withServicesForRunnableInCurrentThread_multiple_threads");

      Thread daemonGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "daemonGet-withServicesForRunnableInCurrentThread_multiple_threads");
      daemonGet.setDaemon(true);

      Thread delayedInParentGroupGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "delayedInParentGroupGet-withServicesForRunnableInCurrentThread_multiple_threads");

      Thread daemonInParentGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "daemonInParentGet-withServicesForRunnableInCurrentThread_multiple_threads");
      daemonInParentGet.setDaemon(true);

      ForkJoinTask<?> forkJoinTask =
          commonPool()
              .submit(
                  () ->
                      assertNoMockServiceRegistrationsInCommonPool(
                          allStartedGate, servicesSetGate));

      Thread withServices =
          new Thread(
              () -> {
                try {
                  allStartedGate.await(TIMEOUT, SECONDS);

                  assertTestableServiceInterface1NegativeRegistration();

                  MockServices.withServicesForRunnableInCurrentThread(
                      () -> {
                        try {
                          assertTestableServiceInterface1Registration();

                          servicesSetGate.await(TIMEOUT, SECONDS);

                          Thread inheritedGet =
                              new Thread(
                                  MockServicesTest::assertTestableServiceInterface1Registration,
                                  "inheritedGet-withServicesForRunnableInCurrentThread_multiple_threads");

                          inheritedGet.start();

                          ForkJoinTask<?> forkJoinTaskInside =
                              commonPool()
                                  .submit(
                                      () ->
                                          assertNoMockServiceRegistrationsInCommonPool(
                                              allStartedGate, servicesSetGate));

                          SECONDS.timedJoin(inheritedGet, TIMEOUT);

                          forkJoinTaskInside.quietlyJoin();

                          MILLISECONDS.sleep(50L);

                          assertTestableServiceInterface1Registration();
                        } catch (InterruptedException
                            | BrokenBarrierException
                            | TimeoutException e) {
                          throw new IllegalStateException(e);
                        }
                      },
                      TestableServiceInterface1.class);

                  assertTestableServiceInterface1NegativeRegistration();
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                  throw new IllegalStateException(e);
                }
              },
              "withServices-withServicesForRunnableInCurrentThread_multiple_threads");

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

      assertTestableServiceInterface1NegativeRegistration();
    }
  }

  @Nested
  class WithServicesForCallableInCurrentThread {
    @Test
    void withCallableNull() {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));

      assertThrows(
          NullPointerException.class,
          () ->
              MockServices.withServicesForCallableInCurrentThread(
                  null, TestableServiceInterface1.class),
          "callable");

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
    }

    @Test
    void clearsServices() throws Exception {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
            assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
            return null;
          });

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
    }

    @Test
    void returnsOne() throws Exception {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));
            assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
            return null;
          },
          TestableServiceInterface1.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
    }

    @Test
    void returnsSeveral() throws Exception {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));
            assertIsTestableServiceInterface2(getServiceInterface(ServiceInterface2.class));
            return null;
          },
          TestableServiceInterface1.class,
          TestableServiceInterface2.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
    }

    @Test
    void returnsRegistered() throws Exception {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
            return null;
          });

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
    }

    @Test
    void returnsRegisteredOverwritingExisting() throws Exception {
      MockServices.setServices(
          TestableServiceInterface1Negative.class,
          TestableServiceInterface2Negative.class,
          TestableServiceInterface3Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertRegisteredService3Interfaces();

      MockServices.withServicesForCallableInCurrentThread(
          () -> {
            assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
            return null;
          });

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertRegisteredService3Interfaces();
    }

    @Test
    void returnsRegistedLast() throws Exception {
      MockServices.setServices(
          TestableServiceInterface1Negative.class, TestableServiceInterface2Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));

      MockServices.withServicesForCallableInCurrentThread(
          MockServicesTest::assertNewServiceInterface3Registrations,
          TestableServiceInterface3.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
    }

    @Test
    void returnsRegistedLastOverwritinigExisting() throws Exception {
      MockServices.setServices(
          TestableServiceInterface1Negative.class,
          TestableServiceInterface2Negative.class,
          TestableServiceInterface3Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertRegisteredService3Interfaces();

      MockServices.withServicesForCallableInCurrentThread(
          MockServicesTest::assertNewServiceInterface3Registrations,
          TestableServiceInterface3.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
      assertIsTestableServiceInterface2Negative(getServiceInterface(ServiceInterface2.class));
      assertRegisteredService3Interfaces();
    }

    @Test
    void setContextClassLoaderThrowsSecurityException() throws InterruptedException {

      MockServices.setServices(TestableServiceInterface1Negative.class);

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));

      AtomicBoolean called = new AtomicBoolean(false);

      Thread setServicesThread =
          new Thread(
              () -> {
                try {
                  MockServices.withServicesForCallableInCurrentThread(
                      () -> {
                        assertIsTestableServiceInterface1Negative(
                            getServiceInterface(ServiceInterface1.class));
                        return null;
                      },
                      TestableServiceInterface1.class);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              "setServicesThread-withServicesForCallableInCurrentThread_callable_thread_setContextClassLoader_throws_SecurityException") {

            @Override
            public void setContextClassLoader(ClassLoader cl) {
              called.compareAndSet(false, true);
              throw new SecurityException();
            }
          };

      setServicesThread.start();

      SECONDS.timedJoin(setServicesThread, TIMEOUT);

      assertThat(called).isTrue();

      assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
    }

    @SuppressWarnings("PMD.AvoidThreadGroup")
    @Test
    void accessibleByCurrentThreadAndThreadStartedWithin()
        throws BrokenBarrierException, InterruptedException, TimeoutException {

      ThreadGroup parentThreadGroup = Thread.currentThread().getThreadGroup().getParent();

      assumeThat(parentThreadGroup).isNotNull();

      MockServices.setServices(TestableServiceInterface1Negative.class);

      assertTestableServiceInterface1NegativeRegistration();

      CyclicBarrier allStartedGate = new CyclicBarrier(7);
      CyclicBarrier servicesSetGate = new CyclicBarrier(6);

      Thread delayedGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "delayedGet-withServicesForCallableInCurrentThread_multiple_threads");

      Thread daemonGet =
          new Thread(
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "daemonGet-withServicesForCallableInCurrentThread_multiple_threads");
      daemonGet.setDaemon(true);

      Thread delayedInParentGroupGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "delayedInParentGroupGet-withServicesForCallableInCurrentThread_multiple_threads");

      Thread daemonInParentGet =
          new Thread(
              parentThreadGroup,
              () ->
                  assertTestableServiceInterface1NegativeRegistrationRunnable(
                      allStartedGate, servicesSetGate),
              "daemonInParentGet-withServicesForCallableInCurrentThread_multiple_threads");
      daemonInParentGet.setDaemon(true);

      ForkJoinTask<?> forkJoinTask =
          commonPool()
              .submit(
                  () ->
                      assertNoMockServiceRegistrationsInCommonPool(
                          allStartedGate, servicesSetGate));

      Thread withServices =
          new Thread(
              () -> {
                try {
                  allStartedGate.await(TIMEOUT, SECONDS);

                  assertTestableServiceInterface1NegativeRegistration();

                  MockServices.withServicesForCallableInCurrentThread(
                      () -> {
                        assertTestableServiceInterface1Registration();

                        servicesSetGate.await(TIMEOUT, SECONDS);

                        Thread inheritedGet =
                            new Thread(
                                MockServicesTest::assertTestableServiceInterface1Registration,
                                "inheritedGet-withServicesForCallableInCurrentThread_multiple_threads");

                        inheritedGet.start();

                        ForkJoinTask<?> forkJoinTaskInside =
                            commonPool()
                                .submit(
                                    () ->
                                        assertNoMockServiceRegistrationsInCommonPool(
                                            allStartedGate, servicesSetGate));

                        SECONDS.timedJoin(inheritedGet, TIMEOUT);

                        forkJoinTaskInside.quietlyJoin();

                        MILLISECONDS.sleep(50L);

                        assertTestableServiceInterface1Registration();

                        return null;
                      },
                      TestableServiceInterface1.class);

                  assertTestableServiceInterface1NegativeRegistration();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }

                assertTestableServiceInterface1NegativeRegistration();
              },
              "withServices-withServicesForCallableInCurrentThread_multiple_threads");

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

      assertTestableServiceInterface1NegativeRegistration();
    }
  }

  @AfterEach
  void tearDown() {
    MockServices.setServices();
  }

  private static final long TIMEOUT = 10L;

  private static <T> T getServiceInterface(Class<T> clazz) {
    Iterator<T> providers = load(clazz).iterator();

    return providers.hasNext() ? providers.next() : null;
  }

  private static void assertNoMockServiceRegistrations() {
    Thread currentThread = Thread.currentThread();

    assertThat(getServiceInterface(ServiceInterface1.class))
        .as("Thread: %s", currentThread)
        .isNull();
    assertThat(getServiceInterface(ServiceInterface2.class))
        .as("Thread: %s", currentThread)
        .isNull();
    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  private static void assertNoMockServiceRegistrationsInCommonPool(
      CyclicBarrier allStartedGate, CyclicBarrier servicesSetGate) {

    try {
      allStartedGate.await(TIMEOUT, SECONDS);
      servicesSetGate.await(TIMEOUT, SECONDS);

      assertNoMockServiceRegistrations();
    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertIsTestableServiceInterface1(ServiceInterface1 serviceInterface1) {
    Thread currentThread = Thread.currentThread();

    assertThat(serviceInterface1).as("Thread: %s", currentThread).isNotNull();
    assertThat(serviceInterface1)
        .as("Thread: %s", currentThread)
        .isInstanceOf(TestableServiceInterface1.class);
    assertThat(serviceInterface1.value()).as("Thread: %s", currentThread).isEqualTo(1);
  }

  private static void assertIsTestableServiceInterface1Negative(
      ServiceInterface1 serviceInterface1) {

    Thread currentThread = Thread.currentThread();

    assumeThat(serviceInterface1).as("Thread: %s", currentThread).isNotNull();
    assertThat(serviceInterface1)
        .as("Thread: %s", currentThread)
        .isInstanceOf(TestableServiceInterface1Negative.class);
    assumeThat(serviceInterface1.value()).as("Thread: %s", currentThread).isEqualTo(-1);
  }

  private static void assertIsTestableServiceInterface2(ServiceInterface2 serviceInterface2) {
    Thread currentThread = Thread.currentThread();

    assertThat(serviceInterface2).as("Thread: %s", currentThread).isNotNull();
    assertThat(serviceInterface2)
        .as("Thread: %s", currentThread)
        .isInstanceOf(ServiceInterface2.class);
    assertThat(serviceInterface2.value()).as("Thread: %s", currentThread).isEqualTo(2);
  }

  private static void assertIsTestableServiceInterface2Negative(
      ServiceInterface2 serviceInterface2) {

    Thread currentThread = Thread.currentThread();

    assertThat(serviceInterface2).as("Thread: %s", currentThread).isNotNull();
    assertThat(serviceInterface2)
        .as("Thread: %s", currentThread)
        .isInstanceOf(TestableServiceInterface2Negative.class);
    assertThat(serviceInterface2.value()).as("Thread: %s", currentThread).isEqualTo(-2);
  }

  private static void assertIsTestableServiceInterface3(ServiceInterface3 serviceInterface3) {
    Thread currentThread = Thread.currentThread();

    assertThat(serviceInterface3).as("Thread: %s", currentThread).isNotNull();
    assertThat(serviceInterface3)
        .as("Thread: %s", currentThread)
        .isInstanceOf(TestableServiceInterface3.class);
    assertThat(serviceInterface3.value()).as("Thread: %s", currentThread).isEqualTo(3);
  }

  private static void assertIsTestableServiceInterface3Negative(
      ServiceInterface3 serviceInterface3) {

    Thread currentThread = Thread.currentThread();

    assertThat(serviceInterface3).as("Thread: %s", currentThread).isNotNull();
    assertThat(serviceInterface3)
        .as("Thread: %s", currentThread)
        .isInstanceOf(TestableServiceInterface3Negative.class);
    assertThat(serviceInterface3.value()).as("Thread: %s", currentThread).isEqualTo(-3);
  }

  private static void assertIsRegisteredServiceInterface3(ServiceInterface3 serviceInterface3) {
    Thread currentThread = Thread.currentThread();

    assertThat(serviceInterface3).as("Thread: %s", currentThread).isNotNull();
    assertThat(serviceInterface3)
        .as("Thread: %s", currentThread)
        .isInstanceOf(RegisteredServiceInterface3.class);
    assertThat(serviceInterface3.value()).as("Thread: %s", currentThread).isEqualTo(333);
  }

  private static void assertRegisteredService3Interfaces() {
    Iterator<ServiceInterface3> providers = load(ServiceInterface3.class).iterator();

    assertIsTestableServiceInterface3Negative(providers.next());
    assertIsRegisteredServiceInterface3(providers.next());
  }

  private static void assertTestableServiceInterface1Registration() {
    assertIsTestableServiceInterface1(getServiceInterface(ServiceInterface1.class));
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  private static void assertTestableServiceInterface1NegativeRegistration() {
    assertIsTestableServiceInterface1Negative(getServiceInterface(ServiceInterface1.class));
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
    assertIsRegisteredServiceInterface3(getServiceInterface(ServiceInterface3.class));
  }

  private static Object assertNewServiceInterface3Registrations() {
    Iterator<ServiceInterface3> providers = load(ServiceInterface3.class).iterator();

    assertIsTestableServiceInterface3(providers.next());
    assertIsRegisteredServiceInterface3(providers.next());

    return null;
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

  private static void assertTestableServiceInterface1NegativeRegistrationRunnable(
      CyclicBarrier allStartedGate, CyclicBarrier servicesSetGate) {

    try {
      allStartedGate.await(TIMEOUT, SECONDS);
      servicesSetGate.await(TIMEOUT, SECONDS);

      assertTestableServiceInterface1NegativeRegistration();
    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }
}
