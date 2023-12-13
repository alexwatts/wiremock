/*
 * Copyright (C) 2015-2023 Thomas Akehurst
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

import static com.github.tomakehurst.wiremock.jetty11.HttpsProxyDetectingHandler.IS_HTTPS_PROXY_REQUEST_ATTRIBUTE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import org.eclipse.jetty.ee10.servlet.ServletApiRequest;
import org.eclipse.jetty.ee10.servlet.ServletApiResponse;
import org.eclipse.jetty.ee10.servlet.ServletChannel;
import org.eclipse.jetty.io.SelectableChannelEndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.Request;

public class JettyUtils {

  private static final boolean IS_JETTY;

  static {
    // do the check only once per classloader / execution
    IS_JETTY = isClassExist("org.eclipse.jetty.server.Request");
  }

  private JettyUtils() {
    // Hide public constructor
  }

  public static boolean isJetty() {
    return IS_JETTY;
  }

  private static boolean isClassExist(String type) {
    try {
      ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
      ClassLoader loader = contextCL == null ? JettyUtils.class.getClassLoader() : contextCL;
      Class.forName(type, false, loader);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static ServletApiResponse unwrapResponse(HttpServletResponse httpServletResponse) {
    if (httpServletResponse instanceof ServletApiResponse) {
      ServletApiResponse unwrapped = ((ServletApiResponse) httpServletResponse);
      return unwrapped;
    }

    return (ServletApiResponse) httpServletResponse;
  }

  public static Socket getTlsSocket(ServletApiResponse response) {
    ServletChannel httpChannel = response.getServletChannel();
    SslConnection.SslEndPoint ep = (SslConnection.SslEndPoint) httpChannel.getEndPoint();
    return ((SocketChannel) ((SelectableChannelEndPoint) ep.getTransport()).getChannel()).socket();
  }

  public static boolean isBrowserProxyRequest(HttpServletRequest request) {
    if (((ServletApiRequest) request).getRequest() != null) {
      Request jettyRequest = ((ServletApiRequest) request).getRequest();
      return Boolean.TRUE.equals(jettyRequest.getAttribute(IS_HTTPS_PROXY_REQUEST_ATTRIBUTE))
          || !serverAuthorityPortMatchesRequestUri(jettyRequest);
    }
    return false;
  }

  private static boolean serverAuthorityPortMatchesRequestUri(Request jettyRequest) {
    int serverAuthorityPort = jettyRequest.getConnectionMetaData().getServerAuthority().getPort();
    return jettyRequest.getHttpURI().getPort() == serverAuthorityPort;
  }
}
