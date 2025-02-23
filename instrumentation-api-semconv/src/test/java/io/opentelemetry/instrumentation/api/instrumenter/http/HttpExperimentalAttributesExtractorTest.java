/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter.http;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.doReturn;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesGetter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpCommonAttributesGetter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesGetter;
import io.opentelemetry.semconv.SemanticAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HttpExperimentalAttributesExtractorTest {

  @Mock HttpClientAttributesGetter<String, String> clientGetter;
  @Mock HttpServerAttributesGetter<String, String> serverGetter;

  @Test
  void shouldExtractRequestAndResponseSizes_client() {
    runTest(clientGetter, HttpExperimentalAttributesExtractor.create(clientGetter));
  }

  @Test
  void shouldExtractRequestAndResponseSizes_server() {
    runTest(serverGetter, HttpExperimentalAttributesExtractor.create(serverGetter));
  }

  void runTest(
      HttpCommonAttributesGetter<String, String> getter,
      AttributesExtractor<String, String> extractor) {

    doReturn(singletonList("123")).when(getter).getHttpRequestHeader("request", "content-length");
    doReturn(singletonList("42"))
        .when(getter)
        .getHttpResponseHeader("request", "response", "content-length");

    AttributesBuilder attributes = Attributes.builder();
    extractor.onStart(attributes, Context.root(), "request");
    assertThat(attributes.build()).isEmpty();

    extractor.onEnd(attributes, Context.root(), "request", "response", null);
    assertThat(attributes.build())
        .containsOnly(
            entry(SemanticAttributes.HTTP_REQUEST_BODY_SIZE, 123L),
            entry(SemanticAttributes.HTTP_RESPONSE_BODY_SIZE, 42L));
  }
}
