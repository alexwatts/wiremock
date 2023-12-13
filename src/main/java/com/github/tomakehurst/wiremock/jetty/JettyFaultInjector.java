/*
 * Copyright (C) 2014-2023 Thomas Akehurst
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
import static com.github.tomakehurst.wiremock.jetty.JettyUtils.unwrapResponse;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.tomakehurst.wiremock.core.FaultInjector;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import org.eclipse.jetty.ee10.servlet.ServletApiResponse;
import org.eclipse.jetty.ee10.servlet.ServletChannel;
import org.eclipse.jetty.io.SelectableChannelEndPoint;
import org.eclipse.jetty.util.BufferUtil;

public class JettyFaultInjector implements FaultInjector {

  private static final byte[] GARBAGE = "lskdu018973t09sylgasjkfg1][]'./.sdlv".getBytes(UTF_8);

  private final ServletApiResponse response;
  private final Socket socket;

  public JettyFaultInjector(HttpServletResponse response) {
    this.response = unwrapResponse(response);
    this.socket = socket();
  }

  @Override
  public void connectionResetByPeer() {
    try {
      socket.setSoLinger(true, 0);
      socket.close();
    } catch (IOException e) {
      throwUnchecked(e);
    }
  }

  @Override
  public void emptyResponseAndCloseConnection() {
    try {
      socket.close();
    } catch (IOException e) {
      throwUnchecked(e);
    }
  }

  @Override
  public void malformedResponseChunk() {
    try {
      response.setStatus(200);
      response.flushBuffer();
      socket.getChannel().write(BufferUtil.toBuffer(GARBAGE));
      socket.close();
    } catch (IOException e) {
      throwUnchecked(e);
    }
  }

  @Override
  public void randomDataAndCloseConnection() {
    try {
      socket.getChannel().write(BufferUtil.toBuffer(GARBAGE));
      socket.close();
    } catch (IOException e) {
      throwUnchecked(e);
    }
  }

  private Socket socket() {
    ServletChannel httpChannel = response.getServletChannel();
    SelectableChannelEndPoint ep = (SelectableChannelEndPoint) httpChannel.getEndPoint();
    return ((SocketChannel) ep.getChannel()).socket();
  }
}
