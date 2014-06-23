/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.internal;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decorator of {@link javax.net.ssl.SSLSocketFactory} applying default ciphers and protocols.
 * List taken from: {@see http://op-co.de/blog/posts/android_ssl_downgrade/}
 */
public class OkHttpSslFactory extends SSLSocketFactory {

  private SSLSocketFactory decoratedFactory;

  static final String[] ENABLED_PROTOCOLS = {"TLSv1.2", "TLSv1.1", "TLSv1"};

  static final String[] ENABLED_CIPHERS = {
          "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
          "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
          "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
          "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
          "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
          "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
          "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
          "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
          "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
          "TLS_RSA_WITH_AES_128_CBC_SHA",
          "TLS_RSA_WITH_AES_256_CBC_SHA",
          "SSL_RSA_WITH_3DES_EDE_CBC_SHA",
          "SSL_RSA_WITH_RC4_128_SHA",
          "SSL_RSA_WITH_RC4_128_MD5",
  };

  private final String[] intersectedCiphers;
  private final String[] intersectedProtocols;

  public OkHttpSslFactory(SSLSocketFactory decoratedFactory, String[] defaultProtocols) {
    this.decoratedFactory = decoratedFactory;
    intersectedCiphers =   intersectWithDefaultFallback(decoratedFactory.getSupportedCipherSuites(),
            ENABLED_CIPHERS);
    intersectedProtocols = intersectWithDefaultFallback(defaultProtocols, ENABLED_PROTOCOLS);
  }

  private String[] intersectWithDefaultFallback(String[] actual, String[] recommended) {
    String[] result = intersect(actual, recommended);

    // Fallback to defaults if intersection is empty
    return result.length == 0 ? actual : result;
  }

  @Override
  public Socket createSocket(String s, int i) throws IOException {
    return decorateSocket(decoratedFactory.createSocket(s, i));
  }

  @Override
  public Socket createSocket(String s, int i, InetAddress inetAddress, int i2) throws IOException {
    return decorateSocket(decoratedFactory.createSocket(s, i, inetAddress, i2));
  }

  @Override
  public Socket createSocket(InetAddress inetAddress, int i) throws IOException {
    return decorateSocket(decoratedFactory.createSocket(inetAddress, i));
  }

  @Override
  public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
          throws IOException {
    return decorateSocket(decoratedFactory.createSocket(socket, host, port, autoClose));
  }

  @Override
  public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress2, int i2)
          throws IOException {
    return decorateSocket(decoratedFactory.createSocket(inetAddress, i, inetAddress2, i2));
  }

  @Override
  public String[] getDefaultCipherSuites() {
    return intersectedCiphers;
  }

  @Override
  public String[] getSupportedCipherSuites() {
    return decoratedFactory.getSupportedCipherSuites();
  }

  private Socket decorateSocket(Socket socket) {
    if (socket instanceof SSLSocket) {
      applyProtocolOrder((SSLSocket) socket);
      applyCipherSuites((SSLSocket) socket);
    }

    return socket;
  }

  private void applyCipherSuites(SSLSocket sslSocket) {
    sslSocket.setEnabledCipherSuites(intersectedCiphers);
  }

  private void applyProtocolOrder(SSLSocket sslSocket) {
    sslSocket.setEnabledProtocols(intersectedProtocols);
  }

  String[] intersect(String[] actual, String[] recommended) {
    List<String> recommendedList = new ArrayList<String>(Arrays.asList(recommended));

    recommendedList.retainAll(Arrays.asList(actual));

    return recommendedList.toArray(new String[recommendedList.size()]);
  }
}

