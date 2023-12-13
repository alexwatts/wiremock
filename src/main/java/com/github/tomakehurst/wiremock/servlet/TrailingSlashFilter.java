/*
 * Copyright (C) 2011-2023 Thomas Akehurst
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
package com.github.tomakehurst.wiremock.servlet;

import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.tomakehurst.wiremock.common.Exceptions;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.diff.DiffEventData;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class TrailingSlashFilter implements Filter {

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {}

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpServletRequest = (HttpServletRequest) request;
    String path = getRequestPathFrom(httpServletRequest);

    StatusAndRedirectExposingHttpServletResponse wrappedResponse =
        new StatusAndRedirectExposingHttpServletResponse(
            (HttpServletResponse) response, path, httpServletRequest);
    chain.doFilter(request, wrappedResponse);
  }

  private static class StatusAndRedirectExposingHttpServletResponse
      extends HttpServletResponseWrapper {

    private final String path;
    private final HttpServletRequest request;

    public StatusAndRedirectExposingHttpServletResponse(
        HttpServletResponse response, String path, HttpServletRequest request) {
      super(response);
      this.path = path;
      this.request = request;
    }

    @Override
    public void sendError(int status) throws IOException {

      ServletContext context = request.getServletContext();
      String realPath = context.getRealPath(getPathPartFromPath(path));
      String pathSuffix = "";
      if (realPath != null) {
        File file = new File(realPath);
        if (file.isDirectory()) {
          pathSuffix = "/";
        }
      }

      var previousPath = (String) request.getAttribute("WireMock.SendError");
      if (previousPath == null) {
        RequestDispatcher dispatcher =
            request.getRequestDispatcher("/__files/" + getPathPartFromPath(path) + pathSuffix);
        request.setAttribute("WireMock.SendError", path);
        try {
          dispatcher.forward(request, getResponse());
        } catch (ServletException se) {
          throw new IOException(se);
        }
      } else {
        Optional.ofNullable(request.getAttribute(ServeEvent.ORIGINAL_SERVE_EVENT_KEY))
            .map(ServeEvent.class::cast)
            .flatMap(ServeEvent::getDiffSubEvent)
            .ifPresentOrElse(
                diffSubEvent -> {
                  final DiffEventData diffData = diffSubEvent.getDataAs(DiffEventData.class);
                  this.setStatus(diffData.getStatus());
                  (this.getResponse()).setContentType((diffData.getContentType()));
                  (this.getResponse()).setCharacterEncoding(StandardCharsets.UTF_8.name());

                  try (final PrintWriter writer = (this.getResponse()).getWriter()) {
                    writer.write(diffData.getReport());
                    writer.flush();
                  } catch (IOException e) {
                    throwUnchecked(e);
                  }
                },
                () ->
                    Exceptions.uncheck(
                        () -> ((HttpServletResponse) this.getResponse()).sendError(404)));
      }
    }

    private String getPathPartFromPath(String location) throws IOException {
      if (isRelativePath(location)) {
        return location;
      }

      URL url = new URL(location);
      return url.getPath();
    }
  }

  private static boolean isRelativePath(String location) {
    return location.matches("^/[^/]{1}.*");
  }

  private String getRequestPathFrom(HttpServletRequest httpServletRequest) throws ServletException {
    try {
      String fullPath =
          new URI(URLEncoder.encode(httpServletRequest.getRequestURI(), UTF_8)).getPath();
      String pathWithoutContext = fullPath.substring(httpServletRequest.getContextPath().length());
      return URLDecoder.decode(pathWithoutContext, UTF_8);
    } catch (URISyntaxException e) {
      throw new ServletException(e);
    }
  }

  @Override
  public void destroy() {}
}
