/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3

import java.io.IOException
import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import mockwebserver3.junit5.internal.MockWebServerInstance
import okhttp3.internal.http2.ErrorCode
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RouteFailureTest {
  private lateinit var client: OkHttpClient

  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private lateinit var server1: MockWebServer
  private lateinit var server2: MockWebServer

  private var listener = RecordingEventListener()

  private var socketFactory: SpecificHostSocketFactory = SpecificHostSocketFactory(defaultAddress = null)

  private val handshakeCertificates = localhost()

  val dns = FakeDns()

  val ipv4 = InetAddress.getByName("203.0.113.1")
  val ipv6 = InetAddress.getByName("2001:db8:ffff:ffff:ffff:ffff:ffff:1")
  val unresolvableIpv4 = InetAddress.getByName("198.51.100.1")
  val unresolvableIpv6 = InetAddress.getByName("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff")

  val refusedStream = MockResponse(
    socketPolicy = SocketPolicy.RESET_STREAM_AT_START,
    http2ErrorCode = ErrorCode.REFUSED_STREAM.httpCode,
  )
  val bodyResponse = MockResponse(body = "body")

  @BeforeEach
  fun setUp(
    server: MockWebServer,
    @MockWebServerInstance("server2") server2: MockWebServer
  ) {
    this.server1 = server
    this.server2 = server2


    client = clientTestRule.newClientBuilder()
      .dns(dns)
      .socketFactory(socketFactory)
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun http2OneBadHostOneGoodNoRetryOnConnectionFailure(
    fastFallback: Boolean
  ) {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(fastFallback)
      .apply {
        retryOnConnectionFailure = false
      }
      .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)
    assertThat(server2.requestCount).isEqualTo(0)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun http2OneBadHostOneGoodRetryOnConnectionFailure(
    fastFallback: Boolean
  ) {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)
    server2.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(ipv6, ipv4)
    socketFactory[ipv6] = server1.inetSocketAddress
    socketFactory[ipv4] = server2.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(fastFallback)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertBody("body")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    // TODO check if we expect a second request to server1, before attempting server2
    assertThat(server1.requestCount).isEqualTo(2)
    assertThat(server2.requestCount).isEqualTo(1)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun http2OneBadHostRetryOnConnectionFailure(
    fastFallback: Boolean
  ) {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(refusedStream)
    server1.enqueue(refusedStream)

    dns[server1.hostName] = listOf(ipv6)
    socketFactory[ipv6] = server1.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(fastFallback)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertFailureMatches("stream was reset: REFUSED_STREAM")

    assertThat(client.routeDatabase.failedRoutes).isEmpty()
    assertThat(server1.requestCount).isEqualTo(1)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun http2OneUnresolvableHost(
    fastFallback: Boolean
  ) {
    enableProtocol(Protocol.HTTP_2)

    val request = Request(server1.url("/"))

    server1.enqueue(bodyResponse)

    dns[server1.hostName] = listOf(unresolvableIpv6, ipv6)
    // Will remain unresolvable
//    socketFactory[unresolvableIpv6] = unresolvableIpv6
    socketFactory[ipv6] = server1.inetSocketAddress

    client = client.newBuilder()
      .fastFallback(fastFallback)
      .apply {
        retryOnConnectionFailure = true
      }
      .build()

    executeSynchronously(request)
      .assertCode(200)

    val failedRoutes = client.routeDatabase.failedRoutes
    assertThat(failedRoutes.single().socketAddress.address).isEqualTo(unresolvableIpv6)
  }

  private fun enableProtocol(protocol: Protocol) {
    enableTls()
    client = client.newBuilder()
      .protocols(listOf(protocol, Protocol.HTTP_1_1))
      .build()
    server1.protocols = client.protocols
    server2.protocols = client.protocols
  }

  private fun enableTls() {
    client = client.newBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager
      )
      .hostnameVerifier(RecordingHostnameVerifier())
      .build()
    server1.useHttps(handshakeCertificates.sslSocketFactory())
    server2.useHttps(handshakeCertificates.sslSocketFactory())
  }

  private fun executeSynchronously(request: Request): RecordedResponse {
    val call = client.newCall(request)
    return try {
      val response = call.execute()
      val bodyString = response.body.string()
      RecordedResponse(request, response, null, bodyString, null)
    } catch (e: IOException) {
      RecordedResponse(request, null, null, null, e)
    }
  }
}
