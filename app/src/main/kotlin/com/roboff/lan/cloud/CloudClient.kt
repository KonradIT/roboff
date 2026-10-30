package com.roboff.lan.cloud
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayList
import java.util.Arrays
import java.util.Base64
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.Set
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
 /** Short-lived HTTPS bootstrap only. This class has no vacuum-control or MQTT methods.  */
 class CloudClient @kotlin.jvm.JvmOverloads constructor(email:kotlin.String?, transport:com.roboff.lan.cloud.CloudClient.Transport? = HttpsTransport()) : Closeable {
    private val email:kotlin.String?
    private val clientId:kotlin.String?
    private val transport:com.roboff.lan.cloud.CloudClient.Transport?
    private val random:java.security.SecureRandom? = java.security.SecureRandom()
    @kotlin.concurrent.Volatile 
    private var closed:Boolean = false
    @kotlin.concurrent.Volatile 
    private var session:JSONObject? = null
    private var loginBase:kotlin.String? = null
    private var country:kotlin.String? = null
    private var countryCode:kotlin.String? = null
    private var legacyCode:Boolean = false
    private var lastCodeRequest:Long = 0
    init  {
         var value: kotlin.String? = if (email == null)"" else email.trim({it <= ' '})
        if (value!!.length > 254 || !value!!.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+".toRegex()))throw java.lang.IllegalArgumentException("Enter your Roborock account email address.")
        this.email = value
        this.transport = transport
        clientId = java.util.Base64.getEncoder().encodeToString(CloudClient.Companion.digest("MD5", (value + CloudClient.Companion.randomUrl(16)).toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
    }
     /** Allows a catalog retry without consuming another email code or exposing account tokens.  */
     fun isAuthenticated(): Boolean {
        return !closed && session != null
    }
     /** Password login is an alternative to email-code login in the supplied Python API.  */
    @kotlin.Throws(CloudException::class) fun loginWithPassword(password:kotlin.String?): CloudCatalog? {
        if (password == null || password.isEmpty())throw CloudException(0, "Enter your Roborock password, or use an email code.")
        ensureRegion(0)
         var response: JSONObject? = call("POST", loginBase, "/api/v1/login", 
        CloudClient.Companion.map("username", email, "password", password, "needtwostepauth", "false"), null, clientHeaders())
        setSession(CloudClient.Companion.checked(response)!!.optJSONObject("data"))
        return fetchCatalog()
    }
     /** Sends one email only on this explicit call; no background resend or login retries.  */
    @kotlin.Throws(CloudException::class) fun requestEmailCode() {
        ensureOpen()
         var now: Long = java.lang.System.currentTimeMillis()
        if (lastCodeRequest > 0 && now - lastCodeRequest < 60000)throw CloudException(9002, "Wait a minute before requesting another email code.", 60)
        for (region in CloudClient.Companion.IOT_BASES!!.indices)  {
            ensureRegion(region)
            legacyCode = country!!.isEmpty() || countryCode!!.isEmpty()
            lastCodeRequest = java.lang.System.currentTimeMillis()
             var response: JSONObject? = if (legacyCode)call("POST", loginBase, "/api/v1/sendEmailCode", CloudClient.Companion.map("username", email, "type", "auth"), null, clientHeaders()) else call("POST", loginBase, "/api/v4/email/code/send", null, CloudClient.Companion.map("email", email, "type", "login", "platform", ""), languageHeaders())
            if (response!!.optInt("code") == 3030 && region + 1 < CloudClient.Companion.IOT_BASES!!.size) {
                loginBase = null
                continue 
            }
            CloudClient.Companion.checked(response)
            return 
        }
        throw CloudException(3030, "The account region could not be determined. Check your Roborock account email.")
    }
    @kotlin.Throws(CloudException::class) fun loginWithEmailCode(code:kotlin.String?): CloudCatalog? {
        if (code == null || !code.trim({it <= ' '}).matches("[0-9]{4,8}".toRegex()))throw CloudException(2018, "Enter the numeric code from your Roborock email.")
        ensureRegion(0)
         var response: JSONObject?
        if (legacyCode || country!!.isEmpty() || countryCode!!.isEmpty()) {
            response = call("POST", loginBase, "/api/v1/loginWithCode", 
            CloudClient.Companion.map("username", email, "verifycode", code.trim({it <= ' '}), "verifycodetype", "AUTH_EMAIL_CODE"), null, clientHeaders())
        } else {
             var secret: kotlin.String? = randomAlphanumeric(16)
             var signature: JSONObject? = CloudClient.Companion.checked(call("POST", loginBase, "/api/v3/key/sign", CloudClient.Companion.map("s", secret), null, clientHeaders()))
             var signed: JSONObject? = signature!!.optJSONObject("data")
            if (signed == null || CloudClient.Companion.text(signed, "k", "")!!.isEmpty())throw CloudClient.Companion.invalidResponse()
             var major: Int = 14
             var minor: Int = 0
            try  {
                 var agreement: JSONObject? = CloudClient.Companion.checked(call("GET", loginBase, "/api/v3/app/agreement/latest", CloudClient.Companion.map("country", country), null, CloudClient.Companion.map("header_clientlang", "en")))
                 var data: JSONObject? = agreement!!.optJSONObject("data")
                if ((data != null) && data.opt("majorVersion") is kotlin.Number && data.opt("minorVersion") is kotlin.Number) {
                    major = data.optInt("majorVersion")
                    minor = data.optInt("minorVersion")
                }
            }catch (ignored:CloudException) {
                ensureOpen() /* Python reference fallback for this optional request. */
            }
             var headers: kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>? = languageHeaders()
            headers!!.put("x-mercy-ks", secret)
            headers!!.put("x-mercy-k", CloudClient.Companion.text(signed, "k", ""))
            headers!!.put("header_appversion", "4.54.02")
            headers!!.put("header_phonesystem", "iOS")
            headers!!.put("header_phonemodel", "iPhone16,1")
            response = call("POST", loginBase, "/api/v4/auth/email/login/code", null, 
            CloudClient.Companion.map("country", country, "countryCode", countryCode, "email", email, "code", code.trim({it <= ' '}), 
            "majorVersion", major.toString(), "minorVersion", minor.toString()), headers)
        }
        setSession(CloudClient.Companion.checked(response)!!.optJSONObject("data"))
        return fetchCatalog()
    }
     /** Fetches only the primary home, device local keys, and room metadata.  */
    @kotlin.Throws(CloudException::class) fun fetchCatalog(): CloudCatalog? {
        ensureOpen()
         var data: JSONObject? = session
        if (data == null)throw CloudException(2010, "Sign in before retrieving your vacuum keys.")
         var rriot: JSONObject? = data.optJSONObject("rriot")
         var reference: JSONObject? = if (rriot == null)null else rriot.optJSONObject("r")
        if (reference == null)throw CloudClient.Companion.invalidResponse()
         var api: kotlin.String? = CloudClient.Companion.validateBase(CloudClient.Companion.text(reference, "a", ""))
         var token: kotlin.String? = CloudClient.Companion.text(data, "token", "")
        if (token!!.isEmpty())throw CloudClient.Companion.invalidResponse()
         var headers: kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>? = clientHeaders()
        headers!!.put("Authorization", token)
         var detail: JSONObject? = CloudClient.Companion.checked(call("GET", loginBase, "/api/v1/getHomeDetail", null, null, headers))!!.optJSONObject("data")
        if (detail == null)throw CloudClient.Companion.invalidResponse()
         var id: kotlin.String? = CloudClient.Companion.text(detail, "rrHomeId", "")
        if (!id!!.matches("[0-9]{1,30}".toRegex()))throw CloudClient.Companion.invalidResponse()
         var path: kotlin.String? = "/v3/user/homes/" + id
         var home: JSONObject? = CloudClient.Companion.checkedHome(call("GET", api, path, null, null, CloudClient.Companion.map("Authorization", CloudClient.Companion.hawk(rriot, path))))!!.optJSONObject("result")
        if (home == null)throw CloudClient.Companion.invalidResponse()
        val warnings = mutableListOf<String>()
        // Some firmware catalogs include rooms directly; older catalogs need the rooms endpoint.
        if (home.optJSONArray("rooms") == null || home.optJSONArray("rooms")!!.length() == 0) {
            try {
                val roomPath = "/user/homes/$id/rooms"
                val rooms = CloudClient.checkedHome(call("GET", api, roomPath, null, null, CloudClient.map("Authorization", CloudClient.hawk(rriot, roomPath))))!!.optJSONArray("result")
                if (rooms != null) home.put("rooms", rooms)
            } catch (_: CloudException) {
                warnings += "Room names could not be retrieved; you can name rooms locally."
            } catch (_: JSONException) {
                warnings += "Room names could not be retrieved; you can name rooms locally."
            }
        }

        val catalog = CloudCatalog.fromHomeData(home, id!!, CloudClient.text(detail, "name", "My home")!!)
        warnings += catalog.warnings
        val devices = catalog.devices.map { device ->
            if (!device.shared) return@map device
            try {
                val roomPath = "/user/deviceshare/query/${device.id}/rooms"
                val rooms = CloudClient.checkedHome(call("GET", api, roomPath, null, null, CloudClient.map("Authorization", CloudClient.hawk(rriot, roomPath))))!!.optJSONArray("result")
                if (rooms != null) device.withRooms(CloudCatalog.readRooms(rooms)) else device
            } catch (_: CloudException) {
                warnings += "Room names for a shared vacuum could not be retrieved."
                device
            }
        }
        ensureOpen()
        return CloudCatalog(catalog.homeId, catalog.homeName, devices, catalog.roomNames, warnings)
    }    @kotlin.Throws(CloudException::class) fun setSessionForTesting(userData:JSONObject?, baseUrl:kotlin.String?) {
        loginBase = CloudClient.Companion.validateBase(baseUrl)
        setSession(userData)
    }
    @kotlin.Throws(CloudException::class) private fun setSession(data:JSONObject?) {
        ensureOpen()
        if ((data == null) || CloudClient.Companion.text(data, "token", "")!!.isEmpty() || (data.optJSONObject("rriot") == null))throw CloudClient.Companion.invalidResponse()
        session = data
    }
    @kotlin.Throws(CloudException::class) private fun ensureRegion(startingRegion:Int) {
        ensureOpen()
        if (loginBase != null)return 
         var last: CloudException? = null
        for (i in startingRegion until CloudClient.Companion.IOT_BASES!!.size)  {
            try  {
                 var response: JSONObject? = CloudClient.Companion.checked(call("POST", CloudClient.Companion.IOT_BASES!!.get(i), "/api/v1/getUrlByEmail", 
                CloudClient.Companion.map("email", email, "needtwostepauth", "false"), null, null))
                 var data: JSONObject? = response!!.optJSONObject("data")
                if (data == null)continue 
                 var foundCountry: kotlin.String? = CloudClient.Companion.text(data, "country", "")
                 var foundCode: kotlin.String? = CloudClient.Companion.text(data, "countrycode", "")
                if (foundCountry!!.isEmpty() && foundCode!!.isEmpty())continue 
                 var base: kotlin.String? = CloudClient.Companion.validateBase(CloudClient.Companion.text(data, "url", ""))
                if (!CloudClient.Companion.IOT_BASES!!.contains(base))throw CloudException(0, "Roborock returned an unsupported account endpoint.")
                loginBase = base
                country = foundCountry
                countryCode = foundCode
                return 
            }catch (failure:CloudException) {
                if ((failure.code == 2003) || (failure.code == 1001) || (failure.code == 9002))throw failure
                last = failure
            }
        }
        if (last != null && last.code == 0)throw last
        throw CloudException(2008, "No Roborock account region was found. Check your email address.")
    }
    @kotlin.Throws(CloudException::class) private fun call(method:kotlin.String?, base:kotlin.String?, path:kotlin.String?, query:kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>?, 
    form:kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>?, headers:kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>?): JSONObject? {
        ensureOpen()
        CloudClient.Companion.validateBase(base)
        if (!path!!.startsWith("/") || path!!.contains("?") || path!!.contains("#"))throw CloudException(0, "Invalid account request.")
        try  {
             var response: JSONObject? = transport!!.request(com.roboff.lan.cloud.CloudClient.Request(method, base, path, query, form, headers))
            ensureOpen()
            if (response == null)throw CloudClient.Companion.invalidResponse()
            return response
        }catch (known:CloudException) {
            throw known
        }catch (failure:IOException) {
            throw CloudException(0, "Could not reach Roborock securely. Check Internet access and try again.")
        }catch (failure:java.lang.RuntimeException) {
            throw CloudException(0, "Could not reach Roborock securely. Check Internet access and try again.")
        }
    }
    private fun clientHeaders(): kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>? {
        return CloudClient.Companion.map("header_clientid", clientId)
    }
    private fun languageHeaders(): kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>? {
         var h: kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>? = clientHeaders()
        h!!.put("header_clientlang", "en")
        return h
    }
    @kotlin.Throws(CloudException::class) private fun ensureOpen() {
        if (closed || java.lang.Thread.currentThread().isInterrupted())throw CloudException(0, "Cloud setup was cancelled.")
    }
    public override fun close() {
        closed = true
        session = null
        transport!!.close()
    }
    private fun randomAlphanumeric(count:Int): kotlin.String? {
         var chars: kotlin.String? = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
         var s: java.lang.StringBuilder? = java.lang.StringBuilder()
        for (i in 0 until count) s!!.append(chars!!.get(random!!.nextInt(chars!!.length)))
        return s.toString()
    }
     /** Injectable HTTPS boundary; Request deliberately has no credential-revealing toString.  */
     open interface Transport : Closeable {
        @kotlin.Throws(IOException::class) open fun request(request:com.roboff.lan.cloud.CloudClient.Request?): JSONObject?
        public override fun close() {}
    }
     class Request internal constructor(method:kotlin.String?, base:kotlin.String?, path:kotlin.String?, query:kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>?, form:kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>?, headers:kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>?) {
         val method:kotlin.String?
         val base:kotlin.String?
         val path:kotlin.String?
         val query:kotlin.collections.Map<kotlin.String?, kotlin.String?>
         val form:kotlin.collections.Map<kotlin.String?, kotlin.String?>
         val headers:kotlin.collections.Map<kotlin.String?, kotlin.String?>
        init  {
            this.method = method
            this.base = base
            this.path = path
            this.query = com.roboff.lan.cloud.CloudClient.Request.Companion.immutable(query)
            this.form = com.roboff.lan.cloud.CloudClient.Request.Companion.immutable(form)
            this.headers = com.roboff.lan.cloud.CloudClient.Request.Companion.immutable(headers)
        }
         companion object {
            private fun immutable(values:kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>?): kotlin.collections.Map<kotlin.String?, kotlin.String?> {
                return if (values == null)emptyMap<kotlin.String?, kotlin.String?>() else Collections.unmodifiableMap<kotlin.String?, kotlin.String?>(LinkedHashMap<kotlin.String?, kotlin.String?>(values))
            }
        }
    }
    internal class HttpsTransport () : com.roboff.lan.cloud.CloudClient.Transport {
        @kotlin.concurrent.Volatile 
        private var active:HttpsURLConnection? = null
        @kotlin.concurrent.Volatile 
        private var closed:Boolean = false
        @kotlin.Throws(IOException::class) public override fun request(request:com.roboff.lan.cloud.CloudClient.Request?): JSONObject? {
            if (closed)throw CloudException(0, "Cloud setup was cancelled.")
            CloudClient.Companion.validateBase(request!!.base)
             var query: kotlin.String? = HttpsTransport.Companion.encode(request!!.query)
             var connection: HttpsURLConnection? = java.net.URI.create(request!!.base + request!!.path + (if (query!!.isEmpty())"" else "?" + query)).toURL().openConnection() as HttpsURLConnection?
            active = connection
            try  {
                if (closed)throw CloudException(0, "Cloud setup was cancelled.")
                connection!!.setInstanceFollowRedirects(false)
                connection!!.setConnectTimeout(15000)
                connection!!.setReadTimeout(20000)
                connection!!.setRequestMethod(request!!.method)
                connection!!.setUseCaches(false)
                connection!!.setRequestProperty("Accept", "application/json")
                for (header:kotlin.collections.Map.Entry<kotlin.String?, kotlin.String?> in request!!.headers!!.entries) connection!!.setRequestProperty(header.key, header.value)
                if (!request!!.form!!.isEmpty()) {
                     var body: ByteArray? = HttpsTransport.Companion.encode(request!!.form)!!.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                    connection!!.setDoOutput(true)
                    connection!!.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection!!.setFixedLengthStreamingMode(body!!.size)
                    try  {
                        connection!!.getOutputStream().use({output -> output.write(body)})
                    }finally  {
                        Arrays.fill(body, 0.toByte())
                    }
                }
                 var status: Int = connection!!.getResponseCode()
                if (status == 429)throw CloudClient.Companion.error(429)
                if (status < 200 || status >= 300)throw CloudException(status, "Roborock returned HTTP " + status + ". Try again later.")
                connection!!.getInputStream().use({input -> ByteArrayOutputStream().use({
                output -> var buffer: ByteArray? = ByteArray(4096)
                 var count: Int
                while ((input.read(buffer).also({ count = it })) != -1) {
                    if (closed || java.lang.Thread.currentThread().isInterrupted())throw CloudException(0, "Cloud setup was cancelled.")
                    if (output.size() + count > 2 * 1024 * 1024)throw CloudException(0, "Roborock returned too much account data.")
                    output.write(buffer, 0, count)
                }
                try  {
                    return JSONObject(output.toString(java.nio.charset.StandardCharsets.UTF_8.name()))
                }catch (invalid:JSONException) {
                    throw CloudException(0, "Roborock returned an unreadable account response.")
                }
                })})
            }finally  {
                if (active === connection)active = null
                connection!!.disconnect()
            }
        }
        public override fun close() {
            closed = true
             var connection: HttpsURLConnection? = active
            if (connection != null)connection.disconnect()
        }
         companion object {
            private fun encode(values:kotlin.collections.Map<kotlin.String?, kotlin.String?>?): kotlin.String? {
                 var pairs: kotlin.collections.MutableList<kotlin.String?>? = java.util.ArrayList<kotlin.String?>()
                for (entry:kotlin.collections.Map.Entry<kotlin.String?, kotlin.String?> in values!!.entries) pairs!!.add(HttpsTransport.Companion.component(entry.key) + "=" + HttpsTransport.Companion.component(entry.value))
                return java.lang.String.join("&", pairs)
            }
            private fun component(value:kotlin.String?): kotlin.String? {
                try  {
                    return URLEncoder.encode(value, "UTF-8")
                }catch (impossible:java.io.UnsupportedEncodingException) {
                    throw java.lang.IllegalStateException(impossible)
                }
            }
        }
    }
     companion object {
        private val IOT_BASES:kotlin.collections.MutableList<kotlin.String?>? = mutableListOf<kotlin.String?>("https://usiot.roborock.com", 
        "https://euiot.roborock.com", "https://cniot.roborock.com", "https://ruiot.roborock.com")
        private val ALLOWED_HOSTS:kotlin.collections.MutableSet<kotlin.String?>? = HashSet<kotlin.String?>(mutableListOf<kotlin.String?>("usiot.roborock.com", "euiot.roborock.com", 
        "cniot.roborock.com", "ruiot.roborock.com", "api-us.roborock.com", "api-eu.roborock.com", 
        "api-cn.roborock.com", "api-ru.roborock.com"))
        @kotlin.Throws(CloudException::class) fun validateBase(base:kotlin.String?): kotlin.String? {
            try  {
                 var uri: java.net.URI? = java.net.URI.create(base)
                if ((!("https" == uri!!.getScheme()) || !CloudClient.Companion.ALLOWED_HOSTS!!.contains(uri!!.getHost())
                 || (uri!!.getRawUserInfo() != null) || (uri!!.getRawQuery() != null) || (uri!!.getRawFragment() != null
                ) || (uri!!.getPort() != -1 && uri!!.getPort() != 443)
                 || ((uri!!.getPath() != null) && !uri!!.getPath().isEmpty() && !(uri!!.getPath() == "/"))))throw java.lang.IllegalArgumentException()
                return "https://" + uri!!.getHost()
            }catch (invalid:java.lang.RuntimeException) {
                throw CloudException(0, "Roborock returned an unexpected secure endpoint.")
            }
        }
        @kotlin.Throws(CloudException::class) private fun checked(response:JSONObject?): JSONObject? {
             var code: Int = response!!.optInt("code", -1)
            if (code == 200)return response
            throw CloudClient.Companion.error(code)
        }
        @kotlin.Throws(CloudException::class) private fun checkedHome(response:JSONObject?): JSONObject? {
            if (response!!.optBoolean("success", false))return response
            throw CloudClient.Companion.error(response!!.optInt("code", -1))
        }
        private fun invalidResponse(): CloudException {
            return CloudException(0, "Roborock returned incomplete account data. Try signing in again.")
        }
        private fun error(code:Int): CloudException {
            when(code){2003 -> return CloudException(code, "Check the format of your email address.")
                2008, 3039 -> return CloudException(code, "No Roborock account was found. Check your email and account region.")
                2010 -> return CloudException(code, "Your sign-in expired. Sign in again to retrieve keys.")
                2018 -> return CloudException(code, "The email code is invalid or expired. Check it or request another code.")
                3006, 3009 -> return CloudException(code, "Open the official Roborock app and accept its current user agreement, then try again.")
                9002, 429 -> return CloudException(code, "Roborock has limited sign-in attempts. Wait before trying again.", 60)
                3030 -> return CloudException(code, "This account belongs to another Roborock region. Try email-code sign-in.")
                else -> return CloudException(code, "Roborock could not sign you in (code " + code + "). Check your details or use an email code.")}
        }
        @kotlin.Throws(CloudException::class) fun hawk(rriot:JSONObject?, path:kotlin.String?): kotlin.String? {
            return CloudClient.Companion.hawk(rriot, path, java.lang.System.currentTimeMillis() / 1000, CloudClient.Companion.randomUrl(6))
        }
        @kotlin.Throws(CloudException::class) fun hawk(rriot:JSONObject?, path:kotlin.String?, timestamp:Long, nonce:kotlin.String?): kotlin.String? {
             var user: kotlin.String? = CloudClient.Companion.text(rriot, "u", "")
             var secret: kotlin.String? = CloudClient.Companion.text(rriot, "s", "")
             var key: kotlin.String? = CloudClient.Companion.text(rriot, "h", "")
            if (user!!.isEmpty() || secret!!.isEmpty() || key!!.isEmpty() || CloudClient.Companion.unsafeHeader(user) || CloudClient.Companion.unsafeHeader(secret))throw CloudClient.Companion.invalidResponse()
             var preimage: kotlin.String? = java.lang.String.join(":", user, secret, nonce, timestamp.toString(), CloudClient.Companion.hex(CloudClient.Companion.digest("MD5", path!!.toByteArray(java.nio.charset.StandardCharsets.UTF_8))), "", "")
            try  {
                 var mac: Mac? = Mac.getInstance("HmacSHA256")
                mac!!.init(SecretKeySpec(key!!.toByteArray(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"))
                 var signature: kotlin.String? = java.util.Base64.getEncoder().encodeToString(mac!!.doFinal(preimage!!.toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
                return "Hawk id=\"" + user + "\",s=\"" + secret + "\",ts=\"" + timestamp + "\",nonce=\"" + nonce + "\",mac=\"" + signature + "\""
            }catch (unavailable:GeneralSecurityException) {
                throw CloudException(0, "This device cannot sign the secure account request.")
            }
        }
        private fun randomUrl(count:Int): kotlin.String? {
             var bytes: ByteArray? = ByteArray(count)
            java.security.SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        private fun text(value:JSONObject?, key:kotlin.String?, fallback:kotlin.String?): kotlin.String? {
            return CloudCatalog.text(value, key!!, fallback ?: "")
        }
        private fun unsafeHeader(value:kotlin.String?): Boolean {
            if (value!!.length > 1024)return true
            for (i in 0 until value!!.length)  {
                 var c: kotlin.Char = value!!.get(i)
                if (java.lang.Character.isISOControl(c) || (c == '"') || (c == '\\'))return true
            }
            return false
        }
        private fun digest(algorithm:kotlin.String?, bytes:ByteArray?): ByteArray? {
            try  {
                return MessageDigest.getInstance(algorithm).digest(bytes)
            }catch (unavailable:GeneralSecurityException) {
                throw java.lang.IllegalStateException("Required digest unavailable")
            }
        }
        private fun hex(bytes:ByteArray?): kotlin.String? {
             var s: java.lang.StringBuilder? = java.lang.StringBuilder()
            for (b:kotlin.Byte in bytes!!) s!!.append(kotlin.String.format(Locale.ROOT, "%02x", b.toInt() and 255))
            return s.toString()
        }
        private fun map(vararg pairs:kotlin.String?): kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>? {
             var result: kotlin.collections.MutableMap<kotlin.String?, kotlin.String?>? = LinkedHashMap<kotlin.String?, kotlin.String?>()
             var i: Int = 0
            while (i < pairs.size) {
                result!!.put(pairs.get(i), pairs.get(i + 1))
                i += 2
            }
            return result
        }
    }
}
