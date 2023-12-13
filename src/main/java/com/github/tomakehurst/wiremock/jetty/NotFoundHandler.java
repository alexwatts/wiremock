/*
 * Copyright (C) 2017-2023 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tomakehurst.wiremock.jetty;

import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;

import com.github.tomakehurst.wiremock.common.Exceptions;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.diff.DiffEventData;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.eclipse.jetty.ee10.servlet.*;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;

public class NotFoundHandler extends ErrorHandler {

  private final ErrorHandler DEFAULT_HANDLER = new ErrorHandler();

  private final ContextHandler mockServiceHandler;

  public NotFoundHandler(ContextHandler mockServiceHandler) {
    this.mockServiceHandler = mockServiceHandler;
  }

  @Override
  public boolean errorPageForMethod(String method) {
    return true;
  }

  @Override
  public boolean handle(Request request, Response response, Callback callback) {
    if (response.getStatus() == 404) {

      Optional.ofNullable(request.getAttribute(ServeEvent.ORIGINAL_SERVE_EVENT_KEY))
          .map(ServeEvent.class::cast)
          .flatMap(ServeEvent::getDiffSubEvent)
          .ifPresentOrElse(
              diffSubEvent -> {
                final DiffEventData diffData = diffSubEvent.getDataAs(DiffEventData.class);
                response.setStatus(diffData.getStatus());
                ((ServletContextResponse) response)
                    .getServletApiResponse()
                    .setContentType((diffData.getContentType()));
                ((ServletContextResponse) response)
                    .getServletApiResponse()
                    .setCharacterEncoding(StandardCharsets.UTF_8.name());

                try (final PrintWriter writer =
                    ((ServletContextResponse) response).getServletApiResponse().getWriter()) {
                  writer.write(diffData.getReport());
                  writer.flush();
                } catch (IOException e) {
                  throwUnchecked(e);
                }
              },
              () ->
                  Exceptions.uncheck(
                      () ->
                          ((ServletContextResponse) response)
                              .getServletApiResponse()
                              .sendError(404)));

    } else {
      try {
        DEFAULT_HANDLER.handle(request, response, Callback.NOOP);
      } catch (Exception e) {
        if (e instanceof IOException) {
          callback.failed(e);
          throwUnchecked(e);
        }
        callback.failed(e);
        throwUnchecked(e);
      }
    }
    callback.succeeded();
    return true;
  }
}
