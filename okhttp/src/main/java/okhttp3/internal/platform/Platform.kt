/*
 * Copyright (C) 2012 Square, Inc.
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal.platform

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.Util.readFieldOrNull
import okhttp3.internal.tls.BasicCertificateChainCleaner
import okhttp3.internal.tls.BasicTrustRootIndex
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Access to platform-specific features.
 *
 * ### Server name indication (SNI)
 *
 * Supported on Android 2.3+.
 *
 * Supported on OpenJDK 7+
 *
 * ### Session Tickets
 *
 * Supported on Android 2.3+.
 *
 * ### Android Traffic Stats (Socket Tagging)
 *
 * Supported on Android 4.0+.
 *
 * ### ALPN (Application Layer Protocol Negotiation)
 *
 * Supported on Android 5.0+. The APIs were present in Android 4.4, but that implementation was
 * unstable.
 *
 * Supported on OpenJDK 8 via the JettyALPN-boot library.
 *
 * Supported on OpenJDK 9+ via SSLParameters and SSLSocket features.
 *
 * ### Trust Manager Extraction
 *
 * Supported on Android 2.3+ and OpenJDK 7+. There are no public APIs to recover the trust
 * manager that was used to create an [SSLSocketFactory].
 *
 * ### Android Cleartext Permit Detection
 *
 * Supported on Android 6.0+ via `NetworkSecurityPolicy`.
 */
open class Platform {

  /** Prefix used on custom headers.  */
  fun getPrefix() = "OkHttp"

  open fun newSSLContext(): SSLContext = SSLContext.getInstance("TLS")

  open fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    val trustManagers = factory.trustManagers!!
    check(trustManagers.size == 1 && trustManagers[0] is X509TrustManager) {
      "Unexpected default trust managers: ${trustManagers.contentToString()}"
    }
    return trustManagers[0] as X509TrustManager
  }

  protected open fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    return try {
      // Attempt to get the trust manager from an OpenJDK socket factory. We attempt this on all
      // platforms in order to support Robolectric, which mixes classes from both Android and the
      // Oracle JDK. Note that we don't support HTTP/2 or other nice features on Robolectric.
      val sslContextClass = Class.forName("sun.security.ssl.SSLContextImpl")
      val context = readFieldOrNull(sslSocketFactory, sslContextClass, "context") ?: return null
      readFieldOrNull(context, X509TrustManager::class.java, "trustManager")
    } catch (e: ClassNotFoundException) {
      null
    }
  }

  /**
   * Configure TLS extensions on `sslSocket` for `route`.
   *
   * @param hostname non-null for client-side handshakes; null for server-side handshakes.
   */
  open fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<@JvmSuppressWildcards Protocol>
  ) {
  }

  /** Called after the TLS handshake to release resources allocated by [configureTlsExtensions]. */
  open fun afterHandshake(sslSocket: SSLSocket) {
  }

  /** Returns the negotiated protocol, or null if no protocol was negotiated.  */
  open fun getSelectedProtocol(socket: SSLSocket): String? = null

  @Throws(IOException::class)
  open fun connectSocket(socket: Socket, address: InetSocketAddress, connectTimeout: Int) {
    socket.connect(address, connectTimeout)
  }

  open fun log(level: Int, message: String, t: Throwable?) {
    val logLevel = if (level == WARN) Level.WARNING else Level.INFO
    logger.log(logLevel, message, t)
  }

  open fun isCleartextTrafficPermitted(hostname: String): Boolean = true

  /**
   * Returns an object that holds a stack trace created at the moment this method is executed. This
   * should be used specifically for [java.io.Closeable] objects and in conjunction with
   * [logCloseableLeak].
   */
  open fun getStackTraceForCloseable(closer: String): Any? {
    return when {
      logger.isLoggable(Level.FINE) -> Throwable(closer) // These are expensive to allocate.
      else -> null
    }
  }

  open fun logCloseableLeak(message: String, stackTrace: Any?) {
    var logMessage = message
    if (stackTrace == null) {
      logMessage += " To see where this was allocated, set the OkHttpClient logger level to " +
          "FINE: Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);"
    }
    log(WARN, logMessage, stackTrace as Throwable?)
  }

  open fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
      BasicCertificateChainCleaner(buildTrustRootIndex(trustManager))

  fun buildCertificateChainCleaner(sslSocketFactory: SSLSocketFactory): CertificateChainCleaner {
    val trustManager = trustManager(sslSocketFactory) ?: throw IllegalStateException(
        "Unable to extract the trust manager on ${get()}, " +
            "sslSocketFactory is ${sslSocketFactory.javaClass}")
    return buildCertificateChainCleaner(trustManager)
  }

  open fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex =
      BasicTrustRootIndex(*trustManager.acceptedIssuers)

  open fun configureSslSocketFactory(socketFactory: SSLSocketFactory) {
  }

  open fun configureTrustManager(trustManager: X509TrustManager?) {
  }

  override fun toString(): String = javaClass.simpleName

  companion object {
    const val INFO = 4
    const val WARN = 5

    private val logger = Logger.getLogger(OkHttpClient::class.java.name)

    @JvmStatic
    fun get(): Platform = PlatformRegistry.platform
  }
}
