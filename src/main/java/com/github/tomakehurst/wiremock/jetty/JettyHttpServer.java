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
import static com.github.tomakehurst.wiremock.common.ResourceUtil.getResource;
import static com.github.tomakehurst.wiremock.core.WireMockApp.ADMIN_CONTEXT_ROOT;
import static java.util.concurrent.Executors.newScheduledThreadPool;

import com.github.tomakehurst.wiremock.common.*;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.core.WireMockApp;
import com.github.tomakehurst.wiremock.http.AdminRequestHandler;
import com.github.tomakehurst.wiremock.http.HttpServer;
import com.github.tomakehurst.wiremock.http.RequestHandler;
import com.github.tomakehurst.wiremock.http.StubRequestHandler;
import com.github.tomakehurst.wiremock.http.trafficlistener.WiremockNetworkTrafficListener;
import com.github.tomakehurst.wiremock.servlet.*;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlets.CrossOriginFilter;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;

public abstract class JettyHttpServer implements HttpServer {
  private static final String FILES_URL_MATCH = String.format("/%s/*", WireMockApp.FILES_ROOT);
  private static final String[] GZIPPABLE_METHODS = new String[] {"POST", "PUT", "PATCH", "DELETE"};
  private static final MutableBoolean STRICT_HTTP_HEADERS_APPLIED = new MutableBoolean(false);
  private static final int MAX_RETRIES = 3;

  protected final Server jettyServer;
  protected final ServerConnector httpConnector;
  protected final ServerConnector httpsConnector;

  protected ScheduledExecutorService scheduledExecutorService;

  public JettyHttpServer(
      Options options,
      AdminRequestHandler adminRequestHandler,
      StubRequestHandler stubRequestHandler) {
    if (!options.getDisableStrictHttpHeaders() && STRICT_HTTP_HEADERS_APPLIED.isFalse()) {
      System.setProperty("org.eclipse.jetty.http.HttpGenerator.STRICT", String.valueOf(true));
      STRICT_HTTP_HEADERS_APPLIED.setTrue();
    }

    jettyServer = createServer(options);

    NetworkTrafficListenerAdapter networkTrafficListenerAdapter =
        new NetworkTrafficListenerAdapter(options.networkTrafficListener());

    if (options.getHttpDisabled()) {
      httpConnector = null;
    } else {
      httpConnector =
          createHttpConnector(
              options.bindAddress(),
              options.portNumber(),
              options.jettySettings(),
              networkTrafficListenerAdapter);
      jettyServer.addConnector(httpConnector);
    }

    if (options.httpsSettings().enabled()) {
      httpsConnector =
          createHttpsConnector(
              options.bindAddress(),
              options.httpsSettings(),
              options.jettySettings(),
              networkTrafficListenerAdapter);
      jettyServer.addConnector(httpsConnector);
    } else {
      httpsConnector = null;
    }

    applyAdditionalServerConfiguration(jettyServer, options);

    final Handler.Abstract handlers =
        createHandler(options, adminRequestHandler, stubRequestHandler);
    jettyServer.setHandler(handlers);

    finalizeSetup(options);
  }

  protected void applyAdditionalServerConfiguration(Server jettyServer, Options options) {}

  protected Handler.Abstract createHandler(
      Options options,
      AdminRequestHandler adminRequestHandler,
      StubRequestHandler stubRequestHandler) {
    Notifier notifier = options.notifier();
    ServletContextHandler adminContext = addAdminContext(adminRequestHandler, notifier);
    ServletContextHandler mockServiceContext =
        addMockServiceContext(
            stubRequestHandler,
            options.filesRoot(),
            options.getAsynchronousResponseSettings(),
            options.getChunkedEncodingPolicy(),
            options.getStubCorsEnabled(),
            options.browserProxySettings().enabled(),
            notifier);

    ContextHandlerCollection handlers = new ContextHandlerCollection();
    Handler.Abstract asyncTimeoutSettingHandler =
        new Handler.Abstract() {

          @Override
          public boolean handle(Request request, Response response, Callback callback)
              throws Exception {
            request
                .getConnectionMetaData()
                .getHttpConfiguration()
                .setIdleTimeout(options.timeout());
            return false;
          }
        };

    handlers.setHandlers(
        ArrayUtils.addAll(
            extensionHandlers(),
            new ContextHandler(adminContext, "/__admin"),
            asyncTimeoutSettingHandler));

    if (options.getGzipDisabled()) {
      handlers.addHandler(new ContextHandler(mockServiceContext, "/"));
    } else {
      addGZipHandler(mockServiceContext, handlers);
    }

    return handlers;
  }

  private void addGZipHandler(
      ServletContextHandler mockServiceContext, ContextHandlerCollection handlers) {
    try {
      GzipHandler gzipHandler = new GzipHandler();
      gzipHandler.addIncludedMethods(GZIPPABLE_METHODS);
      gzipHandler.setHandler(new ContextHandler(mockServiceContext, "/"));
      gzipHandler.setVary(null);
      handlers.addHandler(new ContextHandler(gzipHandler, "/"));
    } catch (Exception e) {
      throwUnchecked(e);
    }
  }

  protected void finalizeSetup(Options options) {
    if (options.jettySettings().getStopTimeout().isEmpty()) {
      jettyServer.setStopTimeout(1000);
    }
  }

  protected Server createServer(Options options) {
    final Server server = new Server(options.threadPoolFactory().buildThreadPool(options));
    final JettySettings jettySettings = options.jettySettings();
    final Optional<Long> stopTimeout = jettySettings.getStopTimeout();
    stopTimeout.ifPresent(server::setStopTimeout);

    return server;
  }

  /** Extend only this method if you want to add additional handlers to Jetty. */
  protected Handler[] extensionHandlers() {
    return new Handler[] {};
  }

  @Override
  public void start() {
    int retryCount = 0;

    while (retryCount < MAX_RETRIES) {
      try {
        jettyServer.start();
        break;
      } catch (IOException bindException) {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
          throw new FatalStartupException(bindException);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    long timeout = System.currentTimeMillis() + 30000;
    while (!jettyServer.isStarted()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        // no-op
      }
      if (System.currentTimeMillis() > timeout) {
        throw new RuntimeException("Server took too long to start up.");
      }
    }
  }

  @Override
  public void stop() {
    try {
      if (scheduledExecutorService != null) {
        scheduledExecutorService.shutdown();
      }

      if (httpConnector != null) {
        httpConnector.getConnectedEndPoints().forEach(EndPoint::close);
      }

      if (httpsConnector != null) {
        httpsConnector.getConnectedEndPoints().forEach(EndPoint::close);
      }

      jettyServer.stop();
      jettyServer.join();
    } catch (TimeoutException ignored) {
    } catch (Exception e) {
      throwUnchecked(e);
    }
  }

  @Override
  public boolean isRunning() {
    return jettyServer.isRunning();
  }

  @Override
  public int port() {
    return httpConnector.getLocalPort();
  }

  @Override
  public int httpsPort() {
    return httpsConnector.getLocalPort();
  }

  public long stopTimeout() {
    return jettyServer.getStopTimeout();
  }

  protected abstract ServerConnector createHttpConnector(
      String bindAddress, int port, JettySettings jettySettings, NetworkTrafficListener listener);

  protected abstract ServerConnector createHttpsConnector(
      String bindAddress,
      HttpsSettings httpsSettings,
      JettySettings jettySettings,
      NetworkTrafficListener listener);

  private ServletContextHandler addMockServiceContext(
      StubRequestHandler stubRequestHandler,
      FileSource fileSource,
      AsynchronousResponseSettings asynchronousResponseSettings,
      Options.ChunkedEncodingPolicy chunkedEncodingPolicy,
      boolean stubCorsEnabled,
      boolean browserProxyingEnabled,
      Notifier notifier) {
    ServletContextHandler mockServiceContext = new ServletContextHandler("/");

    decorateMockServiceContextBeforeConfig(mockServiceContext);

    mockServiceContext.setInitParameter("org.eclipse.jetty.servlet.Default.maxCacheSize", "0");

    mockServiceContext.setBaseResourceAsString(fileSource.getPath());

    mockServiceContext.setInitParameter("org.eclipse.jetty.servlet.Default.dirAllowed", "true");

    mockServiceContext.addServlet(DefaultServlet.class, FILES_URL_MATCH);

    mockServiceContext.setAttribute(
        JettyFaultInjectorFactory.class.getName(), new JettyFaultInjectorFactory());
    mockServiceContext.setAttribute(StubRequestHandler.class.getName(), stubRequestHandler);
    mockServiceContext.setAttribute(Notifier.KEY, notifier);
    mockServiceContext.setAttribute(
        Options.ChunkedEncodingPolicy.class.getName(), chunkedEncodingPolicy);
    mockServiceContext.setAttribute("browserProxyingEnabled", browserProxyingEnabled);
    ServletHolder servletHolder =
        mockServiceContext.addServlet(WireMockHandlerDispatchingServlet.class, "/");
    servletHolder.setInitOrder(1);
    servletHolder.setInitParameter(
        RequestHandler.HANDLER_CLASS_KEY, StubRequestHandler.class.getName());
    servletHolder.setInitParameter(
        FaultInjectorFactory.INJECTOR_CLASS_KEY, JettyFaultInjectorFactory.class.getName());
    servletHolder.setInitParameter(
        WireMockHandlerDispatchingServlet.SHOULD_FORWARD_TO_FILES_CONTEXT, "true");

    if (asynchronousResponseSettings.isEnabled()) {
      scheduledExecutorService = newScheduledThreadPool(asynchronousResponseSettings.getThreads());
      mockServiceContext.setAttribute(
          WireMockHandlerDispatchingServlet.ASYNCHRONOUS_RESPONSE_EXECUTOR,
          scheduledExecutorService);
    }

    mockServiceContext.setAttribute(
        MultipartRequestConfigurer.KEY, buildMultipartRequestConfigurer());

    mockServiceContext.getMimeTypes().addMimeMapping("json", "application/json");
    mockServiceContext.getMimeTypes().addMimeMapping("html", "text/html");
    mockServiceContext.getMimeTypes().addMimeMapping("xml", "application/xml");
    mockServiceContext.getMimeTypes().addMimeMapping("txt", "text/plain");
    mockServiceContext.setWelcomeFiles(
        new String[] {"index.json", "index.html", "index.xml", "index.txt"});

    NotFoundHandler errorHandler = new NotFoundHandler(mockServiceContext);
    mockServiceContext.setErrorHandler(errorHandler);

    mockServiceContext.addFilter(
        ContentTypeSettingFilter.class, FILES_URL_MATCH, EnumSet.of(DispatcherType.FORWARD));
    mockServiceContext.addFilter(
        TrailingSlashFilter.class, FILES_URL_MATCH, EnumSet.allOf(DispatcherType.class));

    if (stubCorsEnabled) {
      addCorsFilter(mockServiceContext);
    }

    decorateMockServiceContextAfterConfig(mockServiceContext);

    return mockServiceContext;
  }

  protected void decorateMockServiceContextBeforeConfig(ServletContextHandler mockServiceContext) {}

  protected void decorateMockServiceContextAfterConfig(ServletContextHandler mockServiceContext) {}

  private ServletContextHandler addAdminContext(
      AdminRequestHandler adminRequestHandler, Notifier notifier) {
    ServletContextHandler adminContext = new ServletContextHandler(ADMIN_CONTEXT_ROOT);

    decorateAdminServiceContextBeforeConfig(adminContext);

    adminContext.setInitParameter("org.eclipse.jetty.servlet.Default.maxCacheSize", "0");

    String javaVendor = System.getProperty("java.vendor");
    if (javaVendor != null && javaVendor.toLowerCase().contains("android")) {
      // Special case for Android, fixes IllegalArgumentException("resource assets not found."):
      //  The Android ClassLoader apparently does not resolve directories.
      //  Furthermore, lib assets will be merged into a single asset directory when a jar file is
      // assimilated into an apk.
      //  As resources can be addressed like "assets/swagger-ui/index.html", a static path element
      // will suffice.
      adminContext.setBaseResourceAsString("assets");
    } else {
      adminContext.setBaseResourceAsString(getResource(JettyHttpServer.class, "assets").toString());
    }

    getResource(JettyHttpServer.class, "assets/swagger-ui/index.html");

    adminContext.setInitParameter("org.eclipse.jetty.servlet.Default.dirAllowed", "true");
    ServletHolder swaggerUiServletHolder =
        adminContext.addServlet(DefaultServlet.class, "/swagger-ui/*");
    swaggerUiServletHolder.setAsyncSupported(false);
    adminContext.addServlet(DefaultServlet.class, "/recorder/*");

    ServletHolder servletHolder =
        adminContext.addServlet(WireMockHandlerDispatchingServlet.class, "/");
    servletHolder.setInitParameter(
        RequestHandler.HANDLER_CLASS_KEY, AdminRequestHandler.class.getName());
    adminContext.setAttribute(AdminRequestHandler.class.getName(), adminRequestHandler);
    adminContext.setAttribute(Notifier.KEY, notifier);

    adminContext.setAttribute(MultipartRequestConfigurer.KEY, buildMultipartRequestConfigurer());

    adminContext.addServlet(NotMatchedServlet.class, "/not-matched");

    addCorsFilter(adminContext);

    decorateAdminServiceContextAfterConfig(adminContext);

    return adminContext;
  }

  protected void decorateAdminServiceContextBeforeConfig(
      ServletContextHandler adminServiceContext) {}

  protected void decorateAdminServiceContextAfterConfig(
      ServletContextHandler adminServiceContext) {}

  private void addCorsFilter(ServletContextHandler context) {
    context.addFilter(buildCorsFilter(), "/*", EnumSet.of(DispatcherType.REQUEST));
  }

  private FilterHolder buildCorsFilter() {
    FilterHolder filterHolder = new FilterHolder(CrossOriginFilter.class);
    filterHolder.setInitParameters(
        Map.of(
            "chainPreflight",
            "false",
            "allowedOrigins",
            "*",
            "allowedHeaders",
            "*",
            "allowedMethods",
            "OPTIONS,GET,POST,PUT,PATCH,DELETE"));
    return filterHolder;
  }

  // Override this for platform-specific impls
  protected MultipartRequestConfigurer buildMultipartRequestConfigurer() {
    return new DefaultMultipartRequestConfigurer();
  }

  private static class NetworkTrafficListenerAdapter implements NetworkTrafficListener {
    private final WiremockNetworkTrafficListener wiremockNetworkTrafficListener;

    NetworkTrafficListenerAdapter(WiremockNetworkTrafficListener wiremockNetworkTrafficListener) {
      this.wiremockNetworkTrafficListener = wiremockNetworkTrafficListener;
    }

    @Override
    public void opened(Socket socket) {
      wiremockNetworkTrafficListener.opened(socket);
    }

    @Override
    public void incoming(Socket socket, ByteBuffer bytes) {
      wiremockNetworkTrafficListener.incoming(socket, bytes);
    }

    @Override
    public void outgoing(Socket socket, ByteBuffer bytes) {
      wiremockNetworkTrafficListener.outgoing(socket, bytes);
    }

    @Override
    public void closed(Socket socket) {
      wiremockNetworkTrafficListener.closed(socket);
    }
  }
}
