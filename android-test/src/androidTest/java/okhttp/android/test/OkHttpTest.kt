/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp.android.test

import android.os.Build
import android.support.test.runner.AndroidJUnit4
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.logging.LoggingEventListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
@RunWith(AndroidJUnit4::class)
class OkHttpTest {
  private lateinit var client: OkHttpClient

  @Before
  fun createClient() {
    client = OkHttpClient.Builder()
        .build()
  }

  @After
  fun cleanup() {
    client.dispatcher.executorService.shutdownNow()
  }

  @Test
  fun testRequest() {
    assumeNetwork()

    val request = Request.Builder().url("https://api.twitter.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testRequestUsesAndroidConscrypt() {
    assumeNetwork()

    val request = Request.Builder().url("https://facebook.com/robots.txt").build()

    var socketClass: String? = null

    val client2 = client.newBuilder()
        .eventListener(object : EventListener() {
          override fun connectionAcquired(call: Call, connection: Connection) {
            socketClass = connection.socket().javaClass.name
          }
        })
        .build()

    val response = client2.newCall(request).execute()

    response.use {
      assertEquals(Protocol.HTTP_2, response.protocol)
      if (Build.VERSION.SDK_INT >= 29) {
        assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
      } else {
        assertEquals(TlsVersion.TLS_1_2, response.handshake?.tlsVersion)
      }
      assertEquals(200, response.code)
      assertEquals("com.android.org.conscrypt.Java8FileDescriptorSocket", socketClass)
    }
  }

  @Test
  fun testHttpRequestBlocked() {
    Assume.assumeTrue(Build.VERSION.SDK_INT >= 23)

    val request = Request.Builder().url("http://api.twitter.com/robots.txt").build()

    try {
      client.newCall(request).execute()
      fail("expected cleartext blocking")
    } catch (_: java.net.UnknownServiceException) {
    }
  }

  @Test
  fun testProxyRequest() {
    assumeNetwork()

    client = client.newBuilder().eventListenerFactory(LoggingEventListener.Factory()).build()

    val proxies = queryProxyList()

    client = client.newBuilder().proxySelector(object : ProxySelector() {
      override fun select(uri: URI): MutableList<Proxy> = proxies.toMutableList()

      override fun connectFailed(p0: URI?, p1: SocketAddress?, p2: IOException?) {}
    }).build()  

    val request = Request.Builder().url("https://api.twitter.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  fun queryProxyList(): List<Proxy> {
    // https://www.proxy-list.download/api/v1
    // https://www.proxy-list.download/api/v1/get?type=http&anon=elite&country=US

    val request = Request.Builder()
        .url("https://www.proxy-list.download/api/v1/get?type=http&anon=elite&country=US").build()

    val response = client.newCall(request).execute()

    val deadProxies =
        listOf("192.0.2.1:8080", "192.0.2.2:8080", "192.0.2.3:8080").mapNotNull { it.toProxy() }

    val liveProxies = response.use {
      response.body!!.string().lines().mapNotNull { it.toProxy() }
    }

    return deadProxies + liveProxies
  }

  private fun String.toProxy(): Proxy? = try {
    val (host, port) = this.split(':', limit = 2)
    Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port.toInt()))
  } catch (_ : IndexOutOfBoundsException) {
    null
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      Assume.assumeNoException(uhe)
    }
  }
}