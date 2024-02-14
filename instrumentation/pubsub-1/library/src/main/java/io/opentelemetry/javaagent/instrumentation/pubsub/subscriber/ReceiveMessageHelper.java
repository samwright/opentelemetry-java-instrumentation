/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pubsub.subscriber;

import com.google.pubsub.v1.PubsubMessage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor;
import io.opentelemetry.javaagent.instrumentation.pubsub.PubsubAttributes;
import io.opentelemetry.javaagent.instrumentation.pubsub.PubsubUtils;
import io.opentelemetry.semconv.ResourceAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import javax.annotation.Nullable;

public class ReceiveMessageHelper {
  private final String subscriptionName;
  private final String subscriptionFullResourceName;
  private final String spanName;

  public static ReceiveMessageHelper of(String subscriptionPath) {
    String subscriptionName = PubsubUtils.getResourceName(subscriptionPath);
    return new ReceiveMessageHelper(
        subscriptionName,
        PubsubUtils.getFullResourceName(subscriptionPath),
        PubsubUtils.getSpanName(
            SemanticAttributes.MessagingOperationValues.RECEIVE, subscriptionName));
  }

  ReceiveMessageHelper(
      String subscriptionName, String subscriptionFullResourceName, String spanName) {
    this.subscriptionName = subscriptionName;
    this.subscriptionFullResourceName = subscriptionFullResourceName;
    this.spanName = spanName;
  }

  public Instrumenter<PubsubMessage, Void> instrumenter() {
    return Instrumenter.<PubsubMessage, Void>builder(
            GlobalOpenTelemetry.get(), PubsubUtils.INSTRUMENTATION_NAME, spanNameExtractor())
        .addAttributesExtractor(new PubsubSubscriberAttributesExtractor())
        .buildConsumerInstrumenter(AttributesGetter.INSTANCE);
  }

  SpanNameExtractor<PubsubMessage> spanNameExtractor() {
    return msg -> spanName;
  }

  class PubsubSubscriberAttributesExtractor implements AttributesExtractor<PubsubMessage, Void> {

    @Override
    public void onStart(AttributesBuilder attributesBuilder, Context context, PubsubMessage msg) {
      attributesBuilder.put(SemanticAttributes.MESSAGING_CLIENT_ID, subscriptionName);
      attributesBuilder.put(ResourceAttributes.CLOUD_RESOURCE_ID, subscriptionFullResourceName);
      attributesBuilder.put(
          SemanticAttributes.MESSAGING_OPERATION,
          SemanticAttributes.MessagingOperationValues.RECEIVE);
      attributesBuilder.put(
          SemanticAttributes.MESSAGING_SYSTEM, PubsubAttributes.MessagingSystemValues.GCP_PUBSUB);
      attributesBuilder.put(SemanticAttributes.MESSAGING_MESSAGE_ID, msg.getMessageId());
      attributesBuilder.put(
          SemanticAttributes.MESSAGING_MESSAGE_ENVELOPE_SIZE, msg.getSerializedSize());
      attributesBuilder.put(SemanticAttributes.MESSAGING_MESSAGE_BODY_SIZE, msg.getData().size());
      if (!msg.getOrderingKey().isEmpty()) {
        attributesBuilder.put(PubsubAttributes.ORDERING_KEY, msg.getOrderingKey());
      }
    }

    @Override
    public void onEnd(
        AttributesBuilder attributesBuilder,
        Context context,
        PubsubMessage msg,
        @Nullable Void unused,
        @Nullable Throwable throwable) {}
  }

  public enum AttributesGetter implements TextMapGetter<PubsubMessage> {
    INSTANCE;

    @Override
    public Iterable<String> keys(PubsubMessage msg) {
      return msg.getAttributesMap().keySet();
    }

    @Nullable
    @Override
    public String get(@Nullable PubsubMessage msg, String key) {
      return msg != null ? msg.getAttributesMap().get(key) : null;
    }
  }
}
