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

import static java.lang.String.format;
import static java.lang.reflect.Modifier.isAbstract;
import static java.lang.reflect.Modifier.isPublic;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * Enables one to register mock services.
 *
 * <h3 id="usage">Usage</h3>
 *
 * <pre><code>
 * &#64;Before
 * public void setUp() {
 *   MockServices.setServices(MyServiceMock.class);
 * }
 *
 * &#64;Test
 * public void getService() {
 *   Iterator&lt;MyService&gt; providers = ServiceLoader.load(MyService.class).iterator();
 *
 *   // providers.next() is MyServiceMock
 * }
 * </code></pre>
 *
 * @see java.util.ServiceLoader
 * @since 1.0
 */
public final class MockServices {

  @SuppressWarnings("CustomClassloader")
  private static final class ServiceClassLoader extends ClassLoader {

    @SuppressWarnings("HardcodedFileSeparator")
    private static final String META_INF_SERVICES = "META-INF/services/";

    private final Set<Class<?>> services;

    @SuppressWarnings("PMD.UseProperClassLoader")
    ServiceClassLoader(Class<?>... services) {
      super(MethodHandles.lookup().lookupClass().getClassLoader());

      Set<Class<?>> classes = stream(services).collect(toSet());

      for (Class<?> clazz : classes) {
        try {
          int mods = clazz.getModifiers();
          if (!isPublic(mods) || isAbstract(mods)) {
            throw new IllegalArgumentException(
                format(
                    Locale.ROOT, "Class %s must be a public non-abstract class", clazz.getName()));
          }
          clazz.getConstructor();
        } catch (NoSuchMethodException e) {
          throw new IllegalArgumentException(
              format(Locale.ROOT, "Class %s has no public no-arg constructor", clazz.getName()), e);
        }
      }

      this.services = unmodifiableSet(classes);
    }

    @Override
    public URL getResource(String name) {
      Enumeration<URL> r;
      try {
        r = getResources(name);
      } catch (IOException ignored) {
        return null;
      }
      return r.hasMoreElements() ? r.nextElement() : null;
    }

    @SuppressWarnings("OverlyBroadThrowsClause")
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      Enumeration<URL> resources = super.getResources(name);

      if (!name.startsWith(META_INF_SERVICES)) {
        return resources;
      }

      Class<?> serviceClass;
      try {
        serviceClass = loadClass(name.substring(META_INF_SERVICES.length()));
      } catch (ClassNotFoundException ignored) {
        return resources;
      }

      Collection<String> impls =
          services
              .stream()
              .filter(serviceClass::isAssignableFrom)
              .map(Class::getName)
              .collect(toSet());

      if (impls.isEmpty()) {
        return resources;
      }

      try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
          PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, UTF_8))) {
        for (String impl : impls) {
          pw.println(impl);
          pw.println("#position=100");
        }
        pw.flush();
        return new ServiceEnumeration(resources, serviceClass, baos);
      }
    }
  }

  @SuppressWarnings({"JdkObsolete", "PMD.ReplaceEnumerationWithIterator"})
  private static final class ServiceEnumeration implements Enumeration<URL> {

    private final Enumeration<URL> resources;
    private final URL url;

    private boolean parent;

    ServiceEnumeration(
        Enumeration<URL> resources, Class<?> serviceClass, ByteArrayOutputStream baos)
        throws MalformedURLException {

      this.resources = resources;
      url =
          new URL(
              "metainfservices", null, 0, serviceClass.getName(), new ServiceStreamHandler(baos));
    }

    @Override
    public boolean hasMoreElements() {
      return !parent || resources.hasMoreElements();
    }

    @Override
    public URL nextElement() {
      if (parent) {
        return resources.nextElement();
      } else {
        parent = true;
        return url;
      }
    }
  }

  private static final class ServiceStreamHandler extends URLStreamHandler {

    @SuppressWarnings("PackageVisibleField")
    final byte[] bytes;

    ServiceStreamHandler(ByteArrayOutputStream baos) {
      bytes = baos.toByteArray();
    }

    @Override
    protected URLConnection openConnection(URL u) {
      // noinspection
      // AnonymousInnerClass,ReturnOfInnerClass,AnonymousInnerClassWithTooManyMethods,InnerClassTooDeeplyNested
      return new URLConnection(u) {

        @Override
        public void connect() {
          // ignore
        }

        @Override
        public InputStream getInputStream() {
          return new ByteArrayInputStream(bytes);
        }
      };
    }
  }

  /**
   * Sets (or resets) the mock services.
   *
   * <p>Clears any previous mock service registrations.
   *
   * <p>Service implementations registered via {@code META-INF/services/} are available after the
   * ones registered by this method.
   *
   * <p>Each mock service class must be public and have a public no-arg constructor.
   *
   * <p>Each mock service class is accessible from all threads, i.e. JVM-global.
   *
   * @param services the mock services; not null
   * @since 1.0
   */
  @SuppressWarnings({"PMD.DoNotUseThreads", "PMD.AvoidThreadGroup"})
  public static void setServices(Class<?>... services) {
    requireNonNull(services, "services");

    // noinspection ClassLoaderInstantiation
    ClassLoader loader = new ServiceClassLoader(services);

    ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
    // noinspection MethodCallInLoopCondition
    while (threadGroup.getParent() != null) {
      threadGroup = threadGroup.getParent();
    }

    while (true) {
      int count = threadGroup.activeCount() + 1;
      // noinspection ObjectAllocationInLoop
      Thread[] threads = new Thread[count];
      int n = threadGroup.enumerate(threads, true);
      if (n < count) {
        for (int i = 0; i < n; i++) {
          threads[i].setContextClassLoader(loader);
        }
        break;
      }
    }
  }

  private MockServices() {}
}
