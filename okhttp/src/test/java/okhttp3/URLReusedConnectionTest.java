/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import okhttp3.internal.Internal;
import okhttp3.internal.RecordingAuthenticator;
import okhttp3.internal.RecordingOkAuthenticator;
import okhttp3.internal.platform.Platform;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.testing.Flaky;
import okhttp3.testing.PlatformRule;
import okhttp3.tls.HandshakeCertificates;
import okio.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import javax.annotation.Nullable;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.*;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Locale.US;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static okhttp3.internal.Internal.addHeaderLenient;
import static okhttp3.internal.Util.immutableListOf;
import static okhttp3.internal.Util.userAgent;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;
import static okhttp3.mockwebserver.SocketPolicy.*;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class URLReusedConnectionTest {
  @Rule public final MockWebServer server = new MockWebServer();
  @Rule public final OkHttpClientTestRule clientTestRule = new OkHttpClientTestRule();

  private OkHttpClient client = clientTestRule.newClient();

  @Before public void setUp() {
    server.setProtocolNegotiationEnabled(false);
  }

  @Test public void postFailsWithBufferedRequestForSmallRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.END_OF_STREAM, 1024);
  }

  @Test public void postFailsWithBufferedRequestForLargeRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.END_OF_STREAM, 16384);
  }

  @Test public void postFailsWithChunkedRequestForSmallRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.CHUNKED, 1024);
  }

  @Test public void postFailsWithChunkedRequestForLargeRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.CHUNKED, 16384);
  }

  @Test public void postFailsWithFixedLengthRequestForSmallRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.FIXED_LENGTH, 1024);
  }

  @Test public void postFailsWithFixedLengthRequestForLargeRequest() throws Exception {
    reusedConnectionFailsWithPost(TransferKind.FIXED_LENGTH, 16384);
  }

  private void reusedConnectionFailsWithPost(TransferKind transferKind, int requestSize)
          throws Exception {
    server.enqueue(new MockResponse()
            .setBody("A")
            .setSocketPolicy(DISCONNECT_AT_END));
    server.enqueue(new MockResponse()
            .setBody("B"));
    server.enqueue(new MockResponse()
            .setBody("C"));

    assertContent("A", getResponse(newRequest("/a")));

    // Give the server time to disconnect.
    Thread.sleep(500);

    // If the request body is larger than OkHttp's replay buffer, the failure may still occur.
    char[] requestBodyChars = new char[requestSize];
    Arrays.fill(requestBodyChars, 'x');
    String requestBody = new String(requestBodyChars);

    for (int j = 0; j < 2; j++) {
      try {
        Response response = getResponse(new Request.Builder()
                .url(server.url("/b"))
                .post(transferKind.newRequestBody(requestBody))
                .build());
        assertContent("B", response);
        break;
      } catch (IOException socketException) {
        // If there's a socket exception, this must have a streamed request body.
        assertThat(j).isEqualTo(0);
        assertThat(transferKind).isIn(TransferKind.CHUNKED, TransferKind.FIXED_LENGTH);
      }
    }

    RecordedRequest requestA = server.takeRequest();
    assertThat(requestA.getPath()).isEqualTo("/a");
    RecordedRequest requestB = server.takeRequest();
    assertThat(requestB.getPath()).isEqualTo("/b");
    assertThat(requestB.getBody().readUtf8()).isEqualTo(requestBody);
  }

  private Response getResponse(Request request) throws IOException {
    return client.newCall(request).execute();
  }

  private void assertContent(String expected, Response response, int limit)
          throws IOException {
    assertThat(readAscii(response.body().byteStream(), limit)).isEqualTo(
            expected);
  }

  private void assertContent(String expected, Response response) throws IOException {
    assertContent(expected, response, Integer.MAX_VALUE);
  }

  /**
   * Reads {@code count} characters from the stream. If the stream is exhausted before {@code count}
   * characters can be read, the remaining characters are returned and the stream is closed.
   */
  private String readAscii(InputStream in, int count) throws IOException {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < count; i++) {
      int value = in.read();
      if (value == -1) {
        in.close();
        break;
      }
      result.append((char) value);
    }
    return result.toString();
  }

  private Request newRequest(String s) {
    return newRequest(server.url(s));
  }

  private Request newRequest(HttpUrl url) {
    return new Request.Builder()
            .url(url)
            .build();
  }
}
