/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp.android.test.compare;

import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.HttpVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Apache HttpClient 5.x.
 *
 * https://hc.apache.org/httpcomponents-client-5.0.x/index.html
 */
class ApacheHttpClientTest {
  private var httpClient = HttpClients.createDefault()

  @AfterEach fun tearDown() {
    httpClient.close()
  }

  @Test fun get() {
    val request = HttpGet("https://google.com/robots.txt")

    httpClient.execute(request).use { response ->
      assertThat(response.code).isEqualTo(200)
      // TODO reenable ALPN later
      assertThat(response.version).isEqualTo(HttpVersion.HTTP_1_1)
    }
  }
}
