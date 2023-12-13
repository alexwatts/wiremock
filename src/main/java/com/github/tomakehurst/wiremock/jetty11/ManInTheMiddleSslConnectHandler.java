/*
 * Copyright (C) 2020-2023 Thomas Akehurst
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
package com.github.tomakehurst.wiremock.jetty11;

import static com.github.tomakehurst.wiremock.common.ParameterUtils.getFirstNonNull;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.util.Promise;

public class ManInTheMiddleSslConnectHandler extends ConnectHandler {

  private final ServerConnector mitmProxyConnector;

  public ManInTheMiddleSslConnectHandler(ServerConnector mitmProxyConnector) {
    this.mitmProxyConnector = mitmProxyConnector;
  }

  @Override
  protected void connectToServer(
      Request request, String ignoredHost, int ignoredPort, Promise<SocketChannel> promise) {
    SocketChannel channel = null;
    try {
      channel = SocketChannel.open();
      channel.socket().setTcpNoDelay(true);
      channel.configureBlocking(false);
      String realHost = getFirstNonNull(mitmProxyConnector.getHost(), "localhost");
      int realPort = mitmProxyConnector.getLocalPort();
      InetSocketAddress address = newConnectAddress(realHost, realPort);
      channel.connect(address);
      promise.succeeded(channel);
    } catch (Throwable x) {
      close(channel);
      promise.failed(x);
    }
  }

  private void close(Closeable closeable) {
    try {
      if (closeable != null) closeable.close();
    } catch (Throwable x) {
      /* Ignore */
    }
  }
}
