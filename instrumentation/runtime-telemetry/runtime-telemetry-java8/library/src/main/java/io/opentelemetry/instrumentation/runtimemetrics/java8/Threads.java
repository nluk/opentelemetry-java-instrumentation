/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.runtimemetrics.java8;

import static io.opentelemetry.api.common.AttributeKey.booleanKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static java.util.Objects.requireNonNull;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.instrumentation.api.internal.SemconvStability;
import io.opentelemetry.instrumentation.runtimemetrics.java8.internal.JmxRuntimeMetricsUtil;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Registers measurements that generate metrics about JVM threads.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Threads.registerObservers(GlobalOpenTelemetry.get());
 * }</pre>
 *
 * <p>Example metrics being exported:
 *
 * <pre>
 *   process.runtime.jvm.threads.count{daemon=true} 2
 *   process.runtime.jvm.threads.count{daemon=false} 5
 * </pre>
 *
 * <p>In case you enable the preview of stable JVM semantic conventions (e.g. by setting the {@code
 * otel.semconv-stability.opt-in} system property to {@code jvm}), the metrics being exported will
 * follow <a
 * href="https://github.com/open-telemetry/semantic-conventions/blob/main/docs/runtime/jvm-metrics.md">the
 * most recent JVM semantic conventions</a>. This is how the example above looks when stable JVM
 * semconv is enabled:
 *
 * <pre>
 *   jvm.thread.count{jvm.thread.daemon=true,jvm.thread.state="waiting"} 1
 *   jvm.thread.count{jvm.thread.daemon=true,jvm.thread.state="runnable"} 2
 *   jvm.thread.count{jvm.thread.daemon=false,jvm.thread.state="waiting"} 2
 *   jvm.thread.count{jvm.thread.daemon=false,jvm.thread.state="runnable"} 3
 * </pre>
 */
public final class Threads {

  // Visible for testing
  static final Threads INSTANCE = new Threads();

  static final AttributeKey<Boolean> DAEMON = booleanKey("daemon");

  // TODO: use the opentelemetry-semconv classes once we have metrics attributes there
  static final AttributeKey<Boolean> JVM_THREAD_DAEMON = booleanKey("jvm.thread.daemon");
  static final AttributeKey<String> JVM_THREAD_STATE = stringKey("jvm.thread.state");

  /** Register observers for java runtime class metrics. */
  public static List<AutoCloseable> registerObservers(OpenTelemetry openTelemetry) {
    return INSTANCE.registerObservers(openTelemetry, ManagementFactory.getThreadMXBean());
  }

  // Visible for testing
  List<AutoCloseable> registerObservers(OpenTelemetry openTelemetry, ThreadMXBean threadBean) {
    Meter meter = JmxRuntimeMetricsUtil.getMeter(openTelemetry);
    List<AutoCloseable> observables = new ArrayList<>();

    if (SemconvStability.emitOldJvmSemconv()) {
      observables.add(
          meter
              .upDownCounterBuilder("process.runtime.jvm.threads.count")
              .setDescription("Number of executing threads")
              .setUnit("{thread}")
              .buildWithCallback(
                  observableMeasurement -> {
                    int daemonThreadCount = threadBean.getDaemonThreadCount();
                    observableMeasurement.record(
                        daemonThreadCount, Attributes.builder().put(DAEMON, true).build());
                    observableMeasurement.record(
                        threadBean.getThreadCount() - daemonThreadCount,
                        Attributes.builder().put(DAEMON, false).build());
                  }));
    }

    if (SemconvStability.emitStableJvmSemconv()) {
      observables.add(
          meter
              .upDownCounterBuilder("jvm.thread.count")
              .setDescription("Number of executing platform threads.")
              .setUnit("{thread}")
              .buildWithCallback(
                  isJava9OrNewer()
                      ? java9AndNewerCallback(threadBean)
                      : java8Callback(threadBean)));
    }

    return observables;
  }

  @Nullable private static final MethodHandle THREAD_INFO_IS_DAEMON;

  static {
    MethodHandle isDaemon;
    try {
      isDaemon =
          MethodHandles.publicLookup()
              .findVirtual(ThreadInfo.class, "isDaemon", MethodType.methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      isDaemon = null;
    }
    THREAD_INFO_IS_DAEMON = isDaemon;
  }

  private static boolean isJava9OrNewer() {
    return THREAD_INFO_IS_DAEMON != null;
  }

  private static Consumer<ObservableLongMeasurement> java8Callback(ThreadMXBean threadBean) {
    return measurement -> {
      int daemonThreadCount = threadBean.getDaemonThreadCount();
      measurement.record(
          daemonThreadCount, Attributes.builder().put(JVM_THREAD_DAEMON, true).build());
      measurement.record(
          threadBean.getThreadCount() - daemonThreadCount,
          Attributes.builder().put(JVM_THREAD_DAEMON, false).build());
    };
  }

  private static Consumer<ObservableLongMeasurement> java9AndNewerCallback(
      ThreadMXBean threadBean) {
    return measurement -> {
      Map<Attributes, Long> counts = new HashMap<>();
      long[] threadIds = threadBean.getAllThreadIds();
      for (ThreadInfo threadInfo : threadBean.getThreadInfo(threadIds)) {
        if (threadInfo == null) {
          continue;
        }
        Attributes threadAttributes = threadAttributes(threadInfo);
        counts.compute(threadAttributes, (k, value) -> value == null ? 1 : value + 1);
      }
      counts.forEach((threadAttributes, count) -> measurement.record(count, threadAttributes));
    };
  }

  private static Attributes threadAttributes(ThreadInfo threadInfo) {
    boolean isDaemon;
    try {
      isDaemon = (boolean) requireNonNull(THREAD_INFO_IS_DAEMON).invoke(threadInfo);
    } catch (Throwable e) {
      throw new IllegalStateException("Unexpected error happened during ThreadInfo#isDaemon()", e);
    }
    String threadState = threadInfo.getThreadState().name().toLowerCase(Locale.ROOT);
    return Attributes.of(JVM_THREAD_DAEMON, isDaemon, JVM_THREAD_STATE, threadState);
  }

  private Threads() {}
}
