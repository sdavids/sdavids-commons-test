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

import java.util.Iterator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public final class MockServicesTest {

  private static <T> T getServiceInterface(Class<T> clazz) {
    Iterator<T> providers = load(clazz).iterator();

    return providers.hasNext() ? providers.next() : null;
  }

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void setServices_null() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("services");

    // noinspection ConstantConditions
    MockServices.setServices((Class<?>[]) null);

    assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  public void setServices_non_public_no_arg_ctor() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(
        "Class io.sdavids.commons.test.NonPublicNoArgCtorServiceInterface1 has no public no-arg constructor");

    MockServices.setServices(NonPublicNoArgCtorServiceInterface1.class);
  }

  @Test
  public void setServices_abstract() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(
        "Class io.sdavids.commons.test.AbstractServiceInterface1 must be public");

    MockServices.setServices(AbstractServiceInterface1.class);
  }

  @Test
  public void setServices_empty() {
    MockServices.setServices();

    assertThat(getServiceInterface(ServiceInterface1.class)).isNull();
    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  public void setServices_one() {
    MockServices.setServices(TestableServiceInterface1.class);

    ServiceInterface1 serviceInterface = getServiceInterface(ServiceInterface1.class);

    assertThat(serviceInterface).isNotNull();
    assertThat(serviceInterface).isInstanceOf(TestableServiceInterface1.class);
    assertThat(serviceInterface.value()).isEqualTo(1);

    assertThat(getServiceInterface(ServiceInterface2.class)).isNull();
  }

  @Test
  public void setServices_several() {
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
