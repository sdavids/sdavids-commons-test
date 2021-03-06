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
import static org.apiguardian.api.API.Status.STABLE;

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
import java.util.concurrent.Callable;
import org.apiguardian.api.API;

/**
 * Enables one to register mock services.
 *
 * <h3>Notes</h3>
 *
 * <p>Tasks executed via {@link java.util.concurrent.Executor}, {@link
 * java.util.concurrent.ExecutorService}, or {@link java.util.concurrent.CompletionService} may not
 * see the registered mock services.
 *
 * <p>Tasks executed with either the {@link java.util.concurrent.Executors#defaultThreadFactory()}
 * or the {@link java.util.concurrent.ForkJoinPool#commonPool()} will not see the registered mock
 * services.
 *
 * <h3 id="usage-set-services">Usage JVM-global</h3>
 *
 * <pre><code>
 * &#64;AfterEach
 * void tearDown() {
 *   // clears all mock services
 *   MockServices.setServices();
 * }
 *
 * &#64;Test
 * void setServices() {
 *   MockServices.setServices(MyServiceMock.class);
 *
 *   Iterator&lt;MyService&gt; providers = ServiceLoader.load(MyService.class).iterator();
 *
 *   // providers.next() is MyServiceMock
 * }
 * </code></pre>
 *
 * <h3 id="usage-current-thread">Usage Current Thread</h3>
 *
 * <pre><code>
 * &#64;Test
 * void withServicesForRunnableInCurrentThread() {
 *   MockServices.withServicesForRunnableInCurrentThread(() -&gt; {
 *     Iterator&lt;MyService&gt; providers = ServiceLoader.load(MyService.class).iterator();
 *
 *     // providers.next() is MyServiceMock
 *   }, MyServiceMock.class);
 * }
 *
 * &#64;Test
 * void withServicesForCallableInCurrentThread() {
 *   MockServices.withServicesForCallableInCurrentThread(() -&gt; {
 *     Iterator&lt;MyService&gt; providers = ServiceLoader.load(MyService.class).iterator();
 *
 *     // providers.next() is MyServiceMock
 *
 *     return null;
 *   }, MyServiceMock.class);
 * }
 * </code></pre>
 *
 * @see java.util.ServiceLoader
 * @since 1.0
 */
@API(status = STABLE, since = "1.0")
public final class MockServices {

  private static final class ServiceClassLoader extends ClassLoader {

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

    final byte[] bytes;

    ServiceStreamHandler(ByteArrayOutputStream baos) {
      bytes = baos.toByteArray();
    }

    @Override
    protected URLConnection openConnection(URL u) {
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
   * @param services the mock services; not null
   * @since 1.0
   */
  @SuppressWarnings("PMD.AvoidThreadGroup")
  public static void setServices(Class<?>... services) {
    requireNonNull(services, "services");

    ClassLoader loader = new ServiceClassLoader(services);

    ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
    while (threadGroup.getParent() != null) {
      threadGroup = threadGroup.getParent();
    }

    while (true) {
      int count = threadGroup.activeCount() + 1;
      Thread[] threads = new Thread[count];
      int n = threadGroup.enumerate(threads, true);
      if (n < count) {
        for (int i = 0; i < n; i++) {
          String threadName = threads[i].getClass().getName();
          if (threadName.endsWith("InnocuousThread")
              || threadName.endsWith("InnocuousForkJoinWorkerThread")) {
            continue;
          }

          try {
            threads[i].setContextClassLoader(loader);
          } catch (SecurityException e) {
            continue;
          }
        }
        break;
      }
    }
  }

  /**
   * Sets (or resets) the mock services for the current thread.
   *
   * <p>Clears any previous mock service registrations of the current thread.
   *
   * <p>Service implementations registered via {@code META-INF/services/} are available after the
   * ones registered by this method.
   *
   * <p>Each mock service class must be public and have a public no-arg constructor.
   *
   * @param services the mock services; not null
   * @return {@code true} if the mock services have been registered; false otherwise
   * @since 2.0
   */
  @API(status = STABLE, since = "2.0")
  public static boolean setServicesForCurrentThread(Class<?>... services) {
    return internalSetServicesForThread(Thread.currentThread(), services);
  }

  /**
   * Sets (or resets) the mock services for the given thread.
   *
   * <p>Clears any previous mock service registrations of that thread.
   *
   * <p>Service implementations registered via {@code META-INF/services/} are available after the
   * ones registered by this method.
   *
   * <p>Each mock service class must be public and have a public no-arg constructor.
   *
   * @param thread the thread; not null
   * @param services the mock services; not null
   * @return {@code true} if the mock services have been registered; false otherwise
   * @since 2.0
   */
  @API(status = STABLE, since = "2.0")
  public static boolean setServicesForThread(Thread thread, Class<?>... services) {
    return internalSetServicesForThread(requireNonNull(thread, "thread"), services);
  }

  /**
   * Sets the mock services for the current thread and executes the given {@link Runnable} task.
   *
   * <p>Clears any previous mock service registrations before executing the given task; resets the
   * previous service registrations afterwards.
   *
   * <p>Service implementations registered via {@code META-INF/services/} are available after the
   * ones registered by this method.
   *
   * <p>Each mock service class must be public and have a public no-arg constructor.
   *
   * <p><em>Note:</em> Threads started within the task will also have the mock services registered.
   *
   * <p>Example:
   *
   * <pre><code>
   * MockServices.withServicesForRunnableInCurrentThread(
   * () -&gt; {
   *   Iterator&lt;MyService&gt; providers  = ServiceLoader.load(MyService.class).iterator();
   *
   *   // providers.next() is MyServiceMock
   *
   *   Thread t = new Thread(() -&gt; {
   *     Iterator&lt;MyService&gt; providers = ServiceLoader.load(MyService.class).iterator();
   *
   *     // providers.next() is MyServiceMock
   *   }).start();
   *   t.join();
   *
   *   return null;
   * },
   * MyServiceMock.class);
   * </code></pre>
   *
   * @param runnable the runnable task; not null
   * @param services the mock services; not null
   * @since 2.0
   */
  @API(status = STABLE, since = "2.0")
  public static void withServicesForRunnableInCurrentThread(
      Runnable runnable, Class<?>... services) {

    withServicesForRunnableInThread(Thread.currentThread(), runnable, services);
  }

  /**
   * Sets the mock services for the current thread and executes the given {@link Callable} task.
   *
   * <p>Clears any previous mock service registrations before executing the given task; resets the
   * previous service registrations afterwards.
   *
   * <p>Service implementations registered via {@code META-INF/services/} are available after the
   * ones registered by this method.
   *
   * <p>Each mock service class must be public and have a public no-arg constructor.
   *
   * <p><em>Note:</em> Threads started within the task will also have the mock services registered.
   *
   * <p>Example:
   *
   * <pre><code>
   * MockServices.withServicesForRunnableInCurrentThread(
   * () -&gt; {
   *   Iterator&lt;MyService&gt; providers  = ServiceLoader.load(MyService.class).iterator();
   *
   *   // providers.next() is MyServiceMock
   *
   *   Thread t = new Thread(() -&gt; {
   *     Iterator&lt;MyService&gt; providers = ServiceLoader.load(MyService.class).iterator();
   *
   *     // providers.next() is MyServiceMock
   *   }).start();
   *   t.join();
   *
   *   return null;
   * },
   * MyServiceMock.class);
   * </code></pre>
   *
   * @param callable the callable task; not null
   * @param services the mock services; not null
   * @throws Exception if the given callable throws an exception
   * @since 2.0
   */
  @API(status = STABLE, since = "2.0")
  public static void withServicesForCallableInCurrentThread(
      Callable<?> callable, Class<?>... services) throws Exception {

    withServicesForCallableInThread(Thread.currentThread(), callable, services);
  }

  private static void withServicesForRunnableInThread(
      Thread thread, Runnable runnable, Class<?>... services) {

    requireNonNull(thread, "thread");
    requireNonNull(runnable, "runnable");
    requireNonNull(services, "services");

    ClassLoader contextClassLoader = thread.getContextClassLoader();

    boolean contextClassLoaderSet = internalSetServicesForThread(thread, services);

    try {
      runnable.run();
    } finally {
      if (contextClassLoaderSet) {
        try {
          thread.setContextClassLoader(contextClassLoader);
        } catch (SecurityException e) {
          // ignore
        }
      }
    }
  }

  private static void withServicesForCallableInThread(
      Thread thread, Callable<?> callable, Class<?>... services) throws Exception {

    requireNonNull(thread, "thread");
    requireNonNull(callable, "callable");
    requireNonNull(services, "services");

    ClassLoader contextClassLoader = thread.getContextClassLoader();

    boolean contextClassLoaderSet = internalSetServicesForThread(thread, services);

    try {
      callable.call();
    } finally {
      if (contextClassLoaderSet) {
        try {
          thread.setContextClassLoader(contextClassLoader);
        } catch (SecurityException e) {
          // ignore
        }
      }
    }
  }

  private static boolean internalSetServicesForThread(Thread thread, Class<?>... services) {
    String threadName = thread.getClass().getName();
    if (threadName.endsWith("InnocuousThread")
        || threadName.endsWith("InnocuousForkJoinWorkerThread")) {
      return false;
    }

    try {
      thread.setContextClassLoader(new ServiceClassLoader(services));
    } catch (SecurityException e) {
      return false;
    }

    return true;
  }

  private MockServices() {}
}
