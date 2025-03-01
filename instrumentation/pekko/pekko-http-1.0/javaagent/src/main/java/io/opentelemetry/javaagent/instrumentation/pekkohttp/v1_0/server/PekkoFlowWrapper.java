/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pekkohttp.v1_0.server;

import io.opentelemetry.context.Context;
import java.util.ArrayDeque;
import java.util.Deque;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
import org.apache.pekko.stream.Attributes;
import org.apache.pekko.stream.BidiShape;
import org.apache.pekko.stream.Inlet;
import org.apache.pekko.stream.Outlet;
import org.apache.pekko.stream.scaladsl.Flow;
import org.apache.pekko.stream.stage.AbstractInHandler;
import org.apache.pekko.stream.stage.AbstractOutHandler;
import org.apache.pekko.stream.stage.GraphStage;
import org.apache.pekko.stream.stage.GraphStageLogic;
import org.apache.pekko.stream.stage.OutHandler;

public class PekkoFlowWrapper
    extends GraphStage<BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest>> {
  private final Inlet<HttpRequest> requestIn = Inlet.create("otel.requestIn");
  private final Outlet<HttpRequest> requestOut = Outlet.create("otel.requestOut");
  private final Inlet<HttpResponse> responseIn = Inlet.create("otel.responseIn");
  private final Outlet<HttpResponse> responseOut = Outlet.create("otel.responseOut");

  private final BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape =
      BidiShape.of(responseIn, responseOut, requestIn, requestOut);

  public static Flow<HttpRequest, HttpResponse, ?> wrap(
      Flow<HttpRequest, HttpResponse, ?> handler) {
    return handler.join(new PekkoFlowWrapper());
  }

  public static Context getContext(OutHandler outHandler) {
    if (outHandler instanceof TracingLogic.ApplicationOutHandler) {
      // We have multiple requests here only when requests are pipelined on the same connection.
      // It appears that these requests are processed one by one so processing next request won't
      // be started before the first one has returned a response, because of this the first request
      // in the queue is always the one that is currently being processed.
      PekkoTracingRequest request =
          ((TracingLogic.ApplicationOutHandler) outHandler).getRequests().peek();
      if (request != null) {
        return request.context;
      }
    }

    return null;
  }

  @Override
  public BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape() {
    return shape;
  }

  @Override
  public GraphStageLogic createLogic(Attributes attributes) {
    return new TracingLogic();
  }

  private class TracingLogic extends GraphStageLogic {
    private final Deque<PekkoTracingRequest> requests = new ArrayDeque<>();

    public TracingLogic() {
      super(shape);

      // server pulls response, pass response from user code to server
      setHandler(
          responseOut,
          new AbstractOutHandler() {
            @Override
            public void onPull() {
              pull(responseIn);
            }

            @Override
            public void onDownstreamFinish(Throwable cause) {
              cancel(responseIn);
            }
          });

      // user code pulls request, pass request from server to user code
      setHandler(
          requestOut,
          new ApplicationOutHandler() {
            @Override
            public void onPull() {
              pull(requestIn);
            }

            @Override
            public void onDownstreamFinish(Throwable cause) {
              // Invoked on errors. Don't complete this stage to allow error-capturing
              cancel(requestIn);
            }
          });

      // new request from server
      setHandler(
          requestIn,
          new AbstractInHandler() {
            @Override
            public void onPush() {
              HttpRequest request = grab(requestIn);
              PekkoTracingRequest tracingRequest = request.getAttribute(PekkoTracingRequest.ATTR_KEY)
                  .orElse(PekkoTracingRequest.EMPTY);
              if (tracingRequest == PekkoTracingRequest.EMPTY) {
                request = (HttpRequest) request.removeAttribute(PekkoTracingRequest.ATTR_KEY);
              }
              // event if span wasn't started we need to push TracingRequest to match response
              // with request
              requests.push(tracingRequest);

              push(requestOut, request);
            }

            @Override
            public void onUpstreamFinish() {
              complete(requestOut);
            }

            @Override
            public void onUpstreamFailure(Throwable exception) {
              fail(requestOut, exception);
            }
          });

      // response from user code
      setHandler(
          responseIn,
          new AbstractInHandler() {
            @Override
            public void onPush() {
              HttpResponse response = grab(responseIn);
              requests.poll();
              push(responseOut, response);
            }

            @Override
            public void onUpstreamFailure(Throwable exception) {
              requests.clear();
              fail(responseOut, exception);
            }

            @Override
            public void onUpstreamFinish() {
              requests.clear();
              completeStage();
            }
          });
    }

    abstract class ApplicationOutHandler extends AbstractOutHandler {
      Deque<PekkoTracingRequest> getRequests() {
        return requests;
      }
    }
  }

}
