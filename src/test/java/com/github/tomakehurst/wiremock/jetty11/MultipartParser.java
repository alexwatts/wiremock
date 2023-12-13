/*
 * Copyright (C) 2018-2023 Thomas Akehurst
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

import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;

import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.servlet.WireMockHttpServletMultipartAdapter;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;
import org.eclipse.jetty.server.internal.MultiPartParser;

public class MultipartParser {

  public static Collection<Request.Part> parse(byte[] body, String contentType) {
    Collection<Part> parts = new ArrayList<>();
    final CustomPart[] currentPartHolder = new CustomPart[1];

    MultiPartParser.Handler handler =
        new MultiPartParser.Handler() {
          @Override
          public void startPart() {
            currentPartHolder[0] = new CustomPart(null, null);
          }

          @Override
          public void parsedField(String name, String value) {
            if (currentPartHolder[0] != null) {
              if (name.equalsIgnoreCase("Content-Disposition")) {
                Map<String, String> dispositionParams = parseDisposition(value);
                currentPartHolder[0] =
                    new CustomPart(
                        dispositionParams.get("name"), dispositionParams.get("filename"));
              }
              currentPartHolder[0].addHeader(name, value);
            }
          }

          @Override
          public boolean content(ByteBuffer item, boolean last) {
            if (currentPartHolder[0] != null && item.hasRemaining()) {
              while (item.hasRemaining()) {
                currentPartHolder[0].writeContent((byte) item.get());
              }
              if (last) {
                parts.add(resolvePart(currentPartHolder[0]));
                currentPartHolder[0] = null;
              }
            }
            return false;
          }

          @Override
          public void earlyEOF() {
            parts.add(resolvePart(currentPartHolder[0]));
            currentPartHolder[0] = null;
          }
        };

    ByteBuffer buffer = ByteBuffer.wrap(body);
    MultiPartParser partParser = new MultiPartParser(handler, extractBoundary(contentType));
    partParser.parse(buffer, true);
    return parts.stream()
        .map(WireMockHttpServletMultipartAdapter::from)
        .collect(Collectors.toList());
  }

  private static Map<String, String> parseDisposition(String disposition) {
    Map<String, String> result = new HashMap<>();

    String[] parts = disposition.split(";");
    for (String part : parts) {
      String[] keyValue = part.trim().split("=");
      if (keyValue.length == 2) {
        String key = keyValue[0].trim();
        String value = keyValue[1].trim();

        if (value.startsWith("\"") && value.endsWith("\"")) {
          value = value.substring(1, value.length() - 1);
        }

        if ("name".equalsIgnoreCase(key) || "filename".equalsIgnoreCase(key)) {
          result.put(key, value);
        }
      }
    }

    return result;
  }

  private static String extractBoundary(String contentType) {
    String boundaryPrefix = "boundary=";
    int boundaryIndex = contentType.indexOf(boundaryPrefix);
    if (boundaryIndex >= 0) {
      return contentType.substring(boundaryIndex + boundaryPrefix.length());
    }
    throw new IllegalArgumentException("Boundary not found in content type: " + contentType);
  }

  private static Part resolvePart(CustomPart customPart) {
    if (isTransferEncodingBase64(customPart)) {
      try {
        return resolveBase64EncodedContent(customPart);
      } catch (IOException e) {
        throwUnchecked(e);
      }
    }
    return customPart;
  }

  private static boolean isTransferEncodingBase64(CustomPart part) {
    return part.getHeader("Content-Transfer-Encoding") != null
        && part.getHeader("Content-Transfer-Encoding").equals("base64");
  }

  public static CustomPart resolveBase64EncodedContent(CustomPart encodedPart) throws IOException {
    InputStream encodedStream = encodedPart.getInputStream();
    byte[] encodedBytes = encodedStream.readAllBytes();

    byte[] decodedBytes = Base64.getDecoder().decode(encodedBytes);

    CustomPart decodedPart =
        new CustomPart(encodedPart.getName(), encodedPart.getSubmittedFileName());

    for (byte b : decodedBytes) {
      decodedPart.writeContent(b);
    }

    for (String headerName : encodedPart.getHeaderNames()) {
      for (String headerValue : encodedPart.getHeaders(headerName)) {
        decodedPart.addHeader(headerName, headerValue);
      }
    }

    return decodedPart;
  }
}
