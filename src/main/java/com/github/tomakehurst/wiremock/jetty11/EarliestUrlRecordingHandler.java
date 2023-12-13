/*
 * Copyright (C) 2023 Thomas Akehurst
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

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;

public class EarliestUrlRecordingHandler extends Handler.Wrapper {

  public static final String EARLIEST_URI_REQUEST_ATTRIBUTE = "wiremock.earliestUri";

  public EarliestUrlRecordingHandler() {}

  @Override
  public boolean handle(Request request, Response response, Callback callback) throws Exception {
    // if (request.getAttribute(EARLIEST_URI_REQUEST_ATTRIBUTE) == null) {
    request.setAttribute(EARLIEST_URI_REQUEST_ATTRIBUTE, request.getHttpURI().getScheme());
    // }
    return false;
  }
}
