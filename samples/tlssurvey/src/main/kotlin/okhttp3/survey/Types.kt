/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.survey

import okio.ByteString

data class Client(val name: String, val enabled: List<SuiteId> = listOf(), val disabled: List<SuiteId> = listOf()) {
  fun enabled(vararg suites: SuiteId): Client {
    return Client(name, suites.toList(), disabled)
  }

  fun disabled(vararg suites: SuiteId): Client {
    return Client(name, enabled, suites.toList())
  }
}

class SuiteId(val id: ByteString, val name: String) {
  override fun equals(other: Any?): Boolean {
    return (other is SuiteId && other.id == id)
  }

  override fun hashCode(): Int {
    return id.hashCode()
  }

  override fun toString(): String {
    return id.hex() + "/" + name
  }
}

data class Record(val java: String, val android: String)
