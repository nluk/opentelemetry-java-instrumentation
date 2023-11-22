/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.executors;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CompletableFutureTest {

  @RegisterExtension
  static final InstrumentationExtension testing = AgentInstrumentationExtension.create();

  @Test
  void multipleCallbacks() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ExecutorService executor2 = Executors.newSingleThreadExecutor();

    String result;
    try {
      result =
          testing.runWithSpan(
              "parent",
              () ->
                  CompletableFuture.supplyAsync(
                          () -> {
                            testing.runWithSpan("supplier", () -> {});
                            try {
                              Thread.sleep(1);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(e);
                            }
                            return "a";
                          },
                          executor)
                      .thenCompose(
                          s -> CompletableFuture.supplyAsync(new AppendingSupplier(s), executor2))
                      .thenApply(
                          s -> {
                            testing.runWithSpan("function", () -> {});
                            return s + "c";
                          })
                      .get());
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    assertThat(result).isEqualTo("abc");

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("supplier").hasKind(SpanKind.INTERNAL).hasParent(trace.getSpan(0)),
                span ->
                    span.hasName("appendingSupplier")
                        .hasKind(SpanKind.INTERNAL)
                        .hasParent(trace.getSpan(0)),
                span ->
                    span.hasName("function")
                        .hasKind(SpanKind.INTERNAL)
                        .hasParent(trace.getSpan(0))));

    executor.shutdown();
    executor2.shutdown();
  }

  @Test
  void supplyAsync() {
    CompletableFuture<String> future =
        testing.runWithSpan(
            "parent",
            () -> CompletableFuture.supplyAsync(() -> testing.runWithSpan("child", () -> "done")));

    assertThat(future.join()).isEqualTo("done");

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("child").hasKind(SpanKind.INTERNAL).hasParent(trace.getSpan(0))));
  }

  @Test
  void thenApply() {
    CompletableFuture<String> future =
        testing.runWithSpan(
            "parent",
            () ->
                CompletableFuture.supplyAsync(() -> "done")
                    .thenApply(result -> testing.runWithSpan("child", () -> result)));

    assertThat(future.join()).isEqualTo("done");

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("child").hasKind(SpanKind.INTERNAL).hasParent(trace.getSpan(0))));
  }

  @Test
  void thenApplyAsync() {
    CompletableFuture<String> future =
        testing.runWithSpan(
            "parent",
            () ->
                CompletableFuture.supplyAsync(() -> "done")
                    .thenApplyAsync(result -> testing.runWithSpan("child", () -> result)));

    assertThat(future.join()).isEqualTo("done");

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("child").hasKind(SpanKind.INTERNAL).hasParent(trace.getSpan(0))));
  }

  @Test
  void thenCompose() {
    CompletableFuture<String> future =
        testing.runWithSpan(
            "parent",
            () ->
                CompletableFuture.supplyAsync(() -> "done")
                    .thenCompose(
                        result ->
                            CompletableFuture.supplyAsync(
                                () -> testing.runWithSpan("child", () -> result))));

    assertThat(future.join()).isEqualTo("done");

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("child").hasKind(SpanKind.INTERNAL).hasParent(trace.getSpan(0))));
  }

  @Test
  void thenComposeAsync() {
    CompletableFuture<String> future =
        testing.runWithSpan(
            "parent",
            () ->
                CompletableFuture.supplyAsync(() -> "done")
                    .thenComposeAsync(
                        result ->
                            CompletableFuture.supplyAsync(
                                () -> testing.runWithSpan("child", () -> result))));

    assertThat(future.join()).isEqualTo("done");

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("child").hasKind(SpanKind.INTERNAL).hasParent(trace.getSpan(0))));
  }

  @Test
  void thenComposeAndApply() {
    CompletableFuture<String> future =
        testing.runWithSpan(
            "parent",
            () ->
                CompletableFuture.supplyAsync(() -> "do")
                    .thenCompose(result -> CompletableFuture.supplyAsync(() -> result + "ne"))
                    .thenApplyAsync(result -> testing.runWithSpan("child", () -> result)));

    assertThat(future.join()).isEqualTo("done");

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("child").hasKind(SpanKind.INTERNAL).hasParent(trace.getSpan(0))));
  }

  @Test
  void sharedCompletableFuture(){

    ExecutorService tokenFetchingExecutor = Executors.newSingleThreadExecutor();

    Supplier<String> longTokenFetchProcess = () ->
      testing.runWithSpan("tokenFetch", () -> {
        try {
          Thread.sleep(5000L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
        return "token";
      });

    // Originally this CompletableFuture would be a result of java.net.http.HttpClient::sendAsync
    // A supplier to ensure the call will be executed only if needed
    Supplier<CompletableFuture<String>> singleTokenFetch =  () -> CompletableFuture.supplyAsync(
        longTokenFetchProcess,
        tokenFetchingExecutor
    );

    ConcurrentHashMap<String, CompletableFuture<String>> fetchStorage = new ConcurrentHashMap<>();

    ExecutorService controller1Executor = Executors.newSingleThreadExecutor();
    ExecutorService controller2Executor = Executors.newSingleThreadExecutor();

    CountDownLatch sync = new CountDownLatch(2);

    CompletableFuture<String> controller1 =
        testing.runWithSpan(
            "controller1-start",
            () -> CompletableFuture.supplyAsync(() -> {
                  sync.countDown();
                  try {
                    sync.await();
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                  return "controller1-start";
                }  , controller1Executor)
                .thenCompose(result -> fetchStorage.computeIfAbsent("COMMON_TOKEN", _ignored -> singleTokenFetch.get()))
                .thenApplyAsync(token -> {
                  testing.runWithSpan("controller1-end", () -> {});
                  return "controller1-end";
                }, controller1Executor)
        );

    CompletableFuture<String> controller2 =
        testing.runWithSpan(
            "controller2-start",
            () -> CompletableFuture.supplyAsync(() -> {
                  sync.countDown();
                  try {
                    sync.await();
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                  return "controller2-start";
                }  , controller2Executor)
                .thenCompose(result -> fetchStorage.computeIfAbsent("COMMON_TOKEN", _ignored -> singleTokenFetch.get()))
                .thenApplyAsync(token -> {
                  testing.runWithSpan("controller2-end", () -> {});
                  return "controller2-end";
                }, controller2Executor)
        );

    CompletableFuture.allOf(controller1, controller2).join();

    assertThat(controller1.join()).isEqualTo("controller1-end");
    assertThat(controller2.join()).isEqualTo("controller2-end");

    List<List<SpanData>> traces = testing.waitForTraces(2);

    Set<String> c1Spans = traces.get(0)
        .stream()
        .map(SpanData::getName)
        .collect(Collectors.toSet());
    Set<String> c2Spans = traces.get(1)
        .stream()
        .map(SpanData::getName)
        .collect(Collectors.toSet());

    assertThat("tokenFetch").satisfiesAnyOf(
        span -> assertThat(c1Spans).contains(span),
        span -> assertThat(c2Spans).contains(span)
    );
    assertThat(c2Spans).contains("controller2-start", "controller2-end");
    assertThat(c1Spans).contains("controller1-start", "controller1-end");
  }

  static final class AppendingSupplier implements Supplier<String> {

    private final String letter;

    AppendingSupplier(String letter) {
      this.letter = letter;
    }

    @Override
    public String get() {
      testing.runWithSpan("appendingSupplier", () -> {});
      return letter + "b";
    }
  }
}
