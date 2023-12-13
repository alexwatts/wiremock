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

import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class CustomPart implements Part {
  private String name;
  private String submittedFileName;
  private ByteArrayOutputStream content = new ByteArrayOutputStream();
  private Map<String, String> headers = new HashMap<>();

  public CustomPart(String name, String submittedFileName) {
    this.name = name;
    this.submittedFileName = submittedFileName;
  }

  public void writeContent(byte b) {
    content.write(b);
  }

  public void addHeader(String name, String value) {
    headers.put(name, value);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return new ByteArrayInputStream(content.toByteArray());
  }

  @Override
  public String getContentType() {
    return getHeader("content-type");
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getSubmittedFileName() {
    return submittedFileName;
  }

  @Override
  public long getSize() {
    return content.size();
  }

  @Override
  public void write(String fileName) throws IOException {
    throw new UnsupportedOperationException("Method not implemented.");
  }

  @Override
  public void delete() throws IOException {
    content.reset();
  }

  @Override
  public String getHeader(String name) {
    return headers.get(name);
  }

  @Override
  public Collection<String> getHeaders(String name) {
    return headers.keySet().stream()
        .filter(key -> key.equalsIgnoreCase(name))
        .map(headers::get)
        .collect(Collectors.toList());
  }

  @Override
  public Collection<String> getHeaderNames() {
    return headers.keySet();
  }
}
