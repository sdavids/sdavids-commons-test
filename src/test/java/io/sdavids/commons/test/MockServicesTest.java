/*
 * Copyright (c) 2017, Sebastian Davids
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Iterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class MockServicesTest {

  private static <T> T getServiceInterface(Class<T> clazz) {
    Iterator<T> providers = load(clazz).iterator();

    return providers.hasNext() ? providers.next() : null;
  }

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
        "Class io.sdavids.commons.test.AbstractServiceInterface1 must be public");

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
  }

  @Test
  void setServices_one() {
    MockServices.setServices(TestableServiceInterface1.class);

    ServiceInterface1 serviceInterface = getServiceInterface(ServiceInterface1.class);

    assertThat(serviceInterface).isNotNull();
    assertThat(serviceInterface).isInstanceOf(TestableServiceInterface1.class);
    assertThat(serviceInterface.value()).isEqualTo(1);

    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  void setServices_several() {
    MockServices.setServices(TestableServiceInterface2.class, TestableServiceInterface1.class);

    ServiceInterface1 serviceInterface1 = getServiceInterface(ServiceInterface1.class);

    assertThat(serviceInterface1).isNotNull();
    assertThat(serviceInterface1).isInstanceOf(TestableServiceInterface1.class);
    assertThat(serviceInterface1.value()).isEqualTo(1);

    ServiceInterface2 serviceInterface2 = getServiceInterface(ServiceInterface2.class);

    assertThat(serviceInterface2).isNotNull();
    assertThat(serviceInterface2).isInstanceOf(ServiceInterface2.class);
    assertThat(serviceInterface2.value()).isEqualTo(2);
  }
}
