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
package okhttp3.internal

import kotlin.jvm.JvmStatic
import okhttp3.HttpUrl
import okhttp3.internal.HttpUrlCommon.canonicalize
import okhttp3.internal.HttpUrlCommon.writePercentDecoded
import okio.Buffer

expect object HttpUrlCommon {
  internal fun Buffer.writePercentDecoded(
    encoded: String,
    pos: Int,
    limit: Int,
    plusIsSpace: Boolean
  )

  internal fun String.canonicalize(
    pos: Int = 0,
    limit: Int = length,
    encodeSet: String,
    alreadyEncoded: Boolean = false,
    strict: Boolean = false,
    plusIsSpace: Boolean = false,
    unicodeAllowed: Boolean = false,
  ): String

}

object CommonHttpUrl {

  internal val HEX_DIGITS =
    charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
  internal const val USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
  internal const val PASSWORD_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
  internal const val PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#"
  internal const val PATH_SEGMENT_ENCODE_SET_URI = "[]"
  internal const val QUERY_ENCODE_SET = " \"'<>#"
  internal const val QUERY_COMPONENT_REENCODE_SET = " \"'<>#&="
  internal const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"
  internal const val QUERY_COMPONENT_ENCODE_SET_URI = "\\^`{|}"
  internal const val FORM_ENCODE_SET = " !\"#$&'()+,/:;<=>?@[\\]^`{|}~"
  internal const val FRAGMENT_ENCODE_SET = ""
  internal const val FRAGMENT_ENCODE_SET_URI = " \"#<>\\^`{|}"

  val HttpUrl.commonEncodedUsername: String
    get() {
      if (username.isEmpty()) return ""
      val usernameStart = scheme.length + 3 // "://".length() == 3.
      val usernameEnd = url.delimiterOffset(":@", usernameStart, url.length)
      return url.substring(usernameStart, usernameEnd)
    }

  val HttpUrl.commonEncodedPassword: String
    get() {
      if (password.isEmpty()) return ""
      val passwordStart = url.indexOf(':', scheme.length + 3) + 1
      val passwordEnd = url.indexOf('@')
      return url.substring(passwordStart, passwordEnd)
    }

  val HttpUrl.commonPathSize: Int get() = pathSegments.size

  val HttpUrl.commonEncodedPath: String
    get() {
      val pathStart = url.indexOf('/', scheme.length + 3) // "://".length() == 3.
      val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
      return url.substring(pathStart, pathEnd)
    }

  val HttpUrl.commonEncodedPathSegments: List<String>
    get() {
      val pathStart = url.indexOf('/', scheme.length + 3)
      val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
      val result = mutableListOf<String>()
      var i = pathStart
      while (i < pathEnd) {
        i++ // Skip the '/'.
        val segmentEnd = url.delimiterOffset('/', i, pathEnd)
        result.add(url.substring(i, segmentEnd))
        i = segmentEnd
      }
      return result
    }

  val HttpUrl.commonEncodedQuery: String?
    get() {
      if (queryNamesAndValues == null) return null // No query.
      val queryStart = url.indexOf('?') + 1
      val queryEnd = url.delimiterOffset('#', queryStart, url.length)
      return url.substring(queryStart, queryEnd)
    }

  val HttpUrl.commonQuery: String?
    get() {
      if (queryNamesAndValues == null) return null // No query.
      val result = StringBuilder()
      queryNamesAndValues.toQueryString(result)
      return result.toString()
    }

  val HttpUrl.commonQuerySize: Int
    get() {
      return if (queryNamesAndValues != null) queryNamesAndValues.size / 2 else 0
    }

  fun HttpUrl.commonQueryParameter(name: String): String? {
    if (queryNamesAndValues == null) return null
    for (i in 0 until queryNamesAndValues.size step 2) {
      if (name == queryNamesAndValues[i]) {
        return queryNamesAndValues[i + 1]
      }
    }
    return null
  }

  val HttpUrl.commonQueryParameterNames: Set<String>
    get() {
      if (queryNamesAndValues == null) return emptySet()
      val result = LinkedHashSet<String>()
      for (i in 0 until queryNamesAndValues.size step 2) {
        result.add(queryNamesAndValues[i]!!)
      }
      return result.readOnly()
    }

  fun HttpUrl.commonQueryParameterValues(name: String): List<String?> {
    if (queryNamesAndValues == null) return emptyList()
    val result = mutableListOf<String?>()
    for (i in 0 until queryNamesAndValues.size step 2) {
      if (name == queryNamesAndValues[i]) {
        result.add(queryNamesAndValues[i + 1])
      }
    }
    return result.readOnly()
  }

  fun HttpUrl.commonQueryParameterName(index: Int): String {
    if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
    return queryNamesAndValues[index * 2]!!
  }

  fun HttpUrl.commonQueryParameterValue(index: Int): String? {
    if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
    return queryNamesAndValues[index * 2 + 1]
  }

  val HttpUrl.commonEncodedFragment: String?
    get() {
      if (fragment == null) return null
      val fragmentStart = url.indexOf('#') + 1
      return url.substring(fragmentStart)
    }

  /** Returns a string for this list of query names and values. */
  internal fun List<String?>.toQueryString(out: StringBuilder) {
    for (i in 0 until size step 2) {
      val name = this[i]
      val value = this[i + 1]
      if (i > 0) out.append('&')
      out.append(name)
      if (value != null) {
        out.append('=')
        out.append(value)
      }
    }
  }

  internal fun HttpUrl.commonRedact(): String {
    return newBuilder("/...")!!
      .username("")
      .password("")
      .build()
      .toString()
  }


  fun HttpUrl.commonResolve(link: String): HttpUrl? = newBuilder(link)?.build()

  fun HttpUrl.commonNewBuilder(): HttpUrl.Builder {
    val result = HttpUrl.Builder()
    result.scheme = scheme
    result.encodedUsername = encodedUsername
    result.encodedPassword = encodedPassword
    result.host = host
    // If we're set to a default port, unset it in case of a scheme change.
    result.port = if (port != commonDefaultPort(scheme)) port else -1
    result.encodedPathSegments.clear()
    result.encodedPathSegments.addAll(encodedPathSegments)
    result.encodedQuery(encodedQuery)
    result.encodedFragment = encodedFragment
    return result
  }

  fun HttpUrl.commonNewBuilder(link: String): HttpUrl.Builder? {
    return try {
      HttpUrl.Builder().parse(this, link)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  fun HttpUrl.commonEquals(other: Any?): Boolean {
    return other is HttpUrl && other.url == url
  }

  fun HttpUrl.commonHashCode(): Int = url.hashCode()

  fun HttpUrl.commonToString(): String = url


  /** Returns 80 if `scheme.equals("http")`, 443 if `scheme.equals("https")` and -1 otherwise. */
  @JvmStatic
  fun commonDefaultPort(scheme: String): Int {
    return when (scheme) {
      "http" -> 80
      "https" -> 443
      else -> -1
    }
  }


  /**
   * @param scheme either "http" or "https".
   */
  fun HttpUrl.Builder.commonScheme(scheme: String) = apply {
    when {
      scheme.equals("http", ignoreCase = true) -> this.scheme = "http"
      scheme.equals("https", ignoreCase = true) -> this.scheme = "https"
      else -> throw IllegalArgumentException("unexpected scheme: $scheme")
    }
  }

  fun HttpUrl.Builder.commonUsername(username: String) = apply {
    this.encodedUsername = username.canonicalize(encodeSet = USERNAME_ENCODE_SET)
  }

  fun HttpUrl.Builder.commonEncodedUsername(encodedUsername: String) = apply {
    this.encodedUsername = encodedUsername.canonicalize(
      encodeSet = USERNAME_ENCODE_SET,
      alreadyEncoded = true
    )
  }

  fun HttpUrl.Builder.commonPassword(password: String) = apply {
    this.encodedPassword = password.canonicalize(encodeSet = PASSWORD_ENCODE_SET)
  }

  fun HttpUrl.Builder.commonEncodedPassword(encodedPassword: String) = apply {
    this.encodedPassword = encodedPassword.canonicalize(
      encodeSet = PASSWORD_ENCODE_SET,
      alreadyEncoded = true
    )
  }

  /**
   * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
   * address.
   */
  fun HttpUrl.Builder.commonHost(host: String) = apply {
    val encoded = host.percentDecode().toCanonicalHost() ?: throw IllegalArgumentException(
      "unexpected host: $host")
    this.host = encoded
  }

  internal fun String.percentDecode(
    pos: Int = 0,
    limit: Int = length,
    plusIsSpace: Boolean = false
  ): String {
    for (i in pos until limit) {
      val c = this[i]
      if (c == '%' || c == '+' && plusIsSpace) {
        // Slow path: the character at i requires decoding!
        val out = Buffer()
        out.writeUtf8(this, pos, i)
        out.writePercentDecoded(this, pos = i, limit = limit, plusIsSpace = plusIsSpace)
        return out.readUtf8()
      }
    }

    // Fast path: no characters in [pos..limit) required decoding.
    return substring(pos, limit)
  }

  fun HttpUrl.Builder.commonPort(port: Int) = apply {
    require(port in 1..65535) { "unexpected port: $port" }
    this.port = port
  }


  fun HttpUrl.Builder.commonAddPathSegment(pathSegment: String) = apply {
    push(pathSegment, 0, pathSegment.length, addTrailingSlash = false, alreadyEncoded = false)
  }

  /**
   * Adds a set of path segments separated by a slash (either `\` or `/`). If `pathSegments`
   * starts with a slash, the resulting URL will have empty path segment.
   */
  fun HttpUrl.Builder.commonAddPathSegments(pathSegments: String): HttpUrl.Builder = commonAddPathSegments(pathSegments, false)

  fun HttpUrl.Builder.commonAddEncodedPathSegment(encodedPathSegment: String) = apply {
    push(encodedPathSegment, 0, encodedPathSegment.length, addTrailingSlash = false,
      alreadyEncoded = true)
  }

  /**
   * Adds a set of encoded path segments separated by a slash (either `\` or `/`). If
   * `encodedPathSegments` starts with a slash, the resulting URL will have empty path segment.
   */
  fun HttpUrl.Builder.commonAddEncodedPathSegments(encodedPathSegments: String): HttpUrl.Builder =
    commonAddPathSegments(encodedPathSegments, true)

  private fun HttpUrl.Builder.commonAddPathSegments(pathSegments: String, alreadyEncoded: Boolean) = apply {
    var offset = 0
    do {
      val segmentEnd = pathSegments.delimiterOffset("/\\", offset, pathSegments.length)
      val addTrailingSlash = segmentEnd < pathSegments.length
      push(pathSegments, offset, segmentEnd, addTrailingSlash, alreadyEncoded)
      offset = segmentEnd + 1
    } while (offset <= pathSegments.length)
  }

  fun HttpUrl.Builder.commonSetPathSegment(index: Int, pathSegment: String) = apply {
    val canonicalPathSegment = pathSegment.canonicalize(encodeSet = PATH_SEGMENT_ENCODE_SET)
    require(!isDot(canonicalPathSegment) && !isDotDot(canonicalPathSegment)) {
      "unexpected path segment: $pathSegment"
    }
    encodedPathSegments[index] = canonicalPathSegment
  }

  fun HttpUrl.Builder.commonSetEncodedPathSegment(index: Int, encodedPathSegment: String) = apply {
    val canonicalPathSegment = encodedPathSegment.canonicalize(
      encodeSet = PATH_SEGMENT_ENCODE_SET,
      alreadyEncoded = true
    )
    encodedPathSegments[index] = canonicalPathSegment
    require(!isDot(canonicalPathSegment) && !isDotDot(canonicalPathSegment)) {
      "unexpected path segment: $encodedPathSegment"
    }
  }

  fun HttpUrl.Builder.commonRemovePathSegment(index: Int) = apply {
    encodedPathSegments.removeAt(index)
    if (encodedPathSegments.isEmpty()) {
      encodedPathSegments.add("") // Always leave at least one '/'.
    }
  }

  fun HttpUrl.Builder.commonEncodedPath(encodedPath: String) = apply {
    require(encodedPath.startsWith("/")) { "unexpected encodedPath: $encodedPath" }
    resolvePath(encodedPath, 0, encodedPath.length)
  }

  fun HttpUrl.Builder.commonQuery(query: String?) = apply {
    this.encodedQueryNamesAndValues = query?.canonicalize(
      encodeSet = QUERY_ENCODE_SET,
      plusIsSpace = true
    )?.toQueryNamesAndValues()
  }

  fun HttpUrl.Builder.commonEncodedQuery(encodedQuery: String?) = apply {
    this.encodedQueryNamesAndValues = encodedQuery?.canonicalize(
      encodeSet = QUERY_ENCODE_SET,
      alreadyEncoded = true,
      plusIsSpace = true
    )?.toQueryNamesAndValues()
  }

  /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
  fun HttpUrl.Builder.commonAddQueryParameter(name: String, value: String?) = apply {
    if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = mutableListOf()
    encodedQueryNamesAndValues!!.add(name.canonicalize(
      encodeSet = QUERY_COMPONENT_ENCODE_SET,
      plusIsSpace = true
    ))
    encodedQueryNamesAndValues!!.add(value?.canonicalize(
      encodeSet = QUERY_COMPONENT_ENCODE_SET,
      plusIsSpace = true
    ))
  }

  /** Adds the pre-encoded query parameter to this URL's query string. */
  fun HttpUrl.Builder.commonAddEncodedQueryParameter(encodedName: String, encodedValue: String?) = apply {
    if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = mutableListOf()
    encodedQueryNamesAndValues!!.add(encodedName.canonicalize(
      encodeSet = QUERY_COMPONENT_REENCODE_SET,
      alreadyEncoded = true,
      plusIsSpace = true
    ))
    encodedQueryNamesAndValues!!.add(encodedValue?.canonicalize(
      encodeSet = QUERY_COMPONENT_REENCODE_SET,
      alreadyEncoded = true,
      plusIsSpace = true
    ))
  }

  fun HttpUrl.Builder.commonSetQueryParameter(name: String, value: String?) = apply {
    removeAllQueryParameters(name)
    addQueryParameter(name, value)
  }

  fun HttpUrl.Builder.commonSetEncodedQueryParameter(encodedName: String, encodedValue: String?) = apply {
    removeAllEncodedQueryParameters(encodedName)
    addEncodedQueryParameter(encodedName, encodedValue)
  }

  fun HttpUrl.Builder.commonRemoveAllQueryParameters(name: String) = apply {
    if (encodedQueryNamesAndValues == null) return this
    val nameToRemove = name.canonicalize(
      encodeSet = QUERY_COMPONENT_ENCODE_SET,
      plusIsSpace = true
    )
    commonRemoveAllCanonicalQueryParameters(nameToRemove)
  }

  fun HttpUrl.Builder.commonRemoveAllEncodedQueryParameters(encodedName: String) = apply {
    if (encodedQueryNamesAndValues == null) return this
    commonRemoveAllCanonicalQueryParameters(encodedName.canonicalize(
      encodeSet = QUERY_COMPONENT_REENCODE_SET,
      alreadyEncoded = true,
      plusIsSpace = true
    ))
  }

  fun HttpUrl.Builder.commonRemoveAllCanonicalQueryParameters(canonicalName: String) {
    for (i in encodedQueryNamesAndValues!!.size - 2 downTo 0 step 2) {
      if (canonicalName == encodedQueryNamesAndValues!![i]) {
        encodedQueryNamesAndValues!!.removeAt(i + 1)
        encodedQueryNamesAndValues!!.removeAt(i)
        if (encodedQueryNamesAndValues!!.isEmpty()) {
          encodedQueryNamesAndValues = null
          return
        }
      }
    }
  }

  fun HttpUrl.Builder.commonFragment(fragment: String?) = apply {
    this.encodedFragment = fragment?.canonicalize(
      encodeSet = FRAGMENT_ENCODE_SET,
      unicodeAllowed = true
    )
  }

  fun HttpUrl.Builder.commonEncodedFragment(encodedFragment: String?) = apply {
    this.encodedFragment = encodedFragment?.canonicalize(
      encodeSet = FRAGMENT_ENCODE_SET,
      alreadyEncoded = true,
      unicodeAllowed = true
    )
  }


  /** Adds a path segment. If the input is ".." or equivalent, this pops a path segment. */
  internal fun HttpUrl.Builder.push(
    input: String,
    pos: Int,
    limit: Int,
    addTrailingSlash: Boolean,
    alreadyEncoded: Boolean
  ) {
    val segment = input.canonicalize(
      pos = pos,
      limit = limit,
      encodeSet = PATH_SEGMENT_ENCODE_SET,
      alreadyEncoded = alreadyEncoded
    )
    if (isDot(segment)) {
      return // Skip '.' path segments.
    }
    if (isDotDot(segment)) {
      pop()
      return
    }
    if (encodedPathSegments[encodedPathSegments.size - 1].isEmpty()) {
      encodedPathSegments[encodedPathSegments.size - 1] = segment
    } else {
      encodedPathSegments.add(segment)
    }
    if (addTrailingSlash) {
      encodedPathSegments.add("")
    }
  }

  internal fun HttpUrl.Builder.isDot(input: String): Boolean {
    return input == "." || input.equals("%2e", ignoreCase = true)
  }

  internal fun HttpUrl.Builder.isDotDot(input: String): Boolean {
    return input == ".." ||
      input.equals("%2e.", ignoreCase = true) ||
      input.equals(".%2e", ignoreCase = true) ||
      input.equals("%2e%2e", ignoreCase = true)
  }

  /**
   * Removes a path segment. When this method returns the last segment is always "", which means
   * the encoded path will have a trailing '/'.
   *
   * Popping "/a/b/c/" yields "/a/b/". In this case the list of path segments goes from ["a",
   * "b", "c", ""] to ["a", "b", ""].
   *
   * Popping "/a/b/c" also yields "/a/b/". The list of path segments goes from ["a", "b", "c"]
   * to ["a", "b", ""].
   */
  internal fun HttpUrl.Builder.pop() {
    val removed = encodedPathSegments.removeAt(encodedPathSegments.size - 1)

    // Make sure the path ends with a '/' by either adding an empty string or clearing a segment.
    if (removed.isEmpty() && encodedPathSegments.isNotEmpty()) {
      encodedPathSegments[encodedPathSegments.size - 1] = ""
    } else {
      encodedPathSegments.add("")
    }
  }

  internal fun HttpUrl.Builder.resolvePath(input: String, startPos: Int, limit: Int) {
    var pos = startPos
    // Read a delimiter.
    if (pos == limit) {
      // Empty path: keep the base path as-is.
      return
    }
    val c = input[pos]
    if (c == '/' || c == '\\') {
      // Absolute path: reset to the default "/".
      encodedPathSegments.clear()
      encodedPathSegments.add("")
      pos++
    } else {
      // Relative path: clear everything after the last '/'.
      encodedPathSegments[encodedPathSegments.size - 1] = ""
    }

    // Read path segments.
    var i = pos
    while (i < limit) {
      val pathSegmentDelimiterOffset = input.delimiterOffset("/\\", i, limit)
      val segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit
      push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true)
      i = pathSegmentDelimiterOffset
      if (segmentHasTrailingSlash) i++
    }
  }

  /**
   * Cuts this string up into alternating parameter names and values. This divides a query string
   * like `subject=math&easy&problem=5-2=3` into the list `["subject", "math", "easy", null,
   * "problem", "5-2=3"]`. Note that values may be null and may contain '=' characters.
   */
  internal fun String.toQueryNamesAndValues(): MutableList<String?> {
    val result = mutableListOf<String?>()
    var pos = 0
    while (pos <= length) {
      var ampersandOffset = indexOf('&', pos)
      if (ampersandOffset == -1) ampersandOffset = length

      val equalsOffset = indexOf('=', pos)
      if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
        result.add(substring(pos, ampersandOffset))
        result.add(null) // No value for this name.
      } else {
        result.add(substring(pos, equalsOffset))
        result.add(substring(equalsOffset + 1, ampersandOffset))
      }
      pos = ampersandOffset + 1
    }
    return result
  }
}
