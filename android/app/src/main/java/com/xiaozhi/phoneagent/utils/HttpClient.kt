package com.xiaozhi.phoneagent.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClient {
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var client: OkHttpClient

    fun initialize(context: Context) {
        cookieJar = PersistentCookieJar(context)
        client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun get(): OkHttpClient {
        check(::client.isInitialized) { "HttpClient not initialized. Call initialize() first." }
        return client
    }

    fun clearCookies() {
        if (::cookieJar.isInitialized) {
            cookieJar.clear()
        }
    }

    fun hasCookie(url: String, cookieName: String): Boolean {
        if (!::cookieJar.isInitialized) return false
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        return cookieJar.loadForRequest(httpUrl).any { it.name == cookieName }
    }
}

class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("http_cookies", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cache = mutableListOf<Cookie>()

    init {
        loadFromPrefs()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            cache.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt >= now) {
                cache.add(cookie)
            }
        }
        saveToPrefs()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val valid = cache.filter { it.expiresAt >= now }
        if (valid.size != cache.size) {
            cache.clear()
            cache.addAll(valid)
            saveToPrefs()
        }
        return valid.filter { it.matches(url) }
    }

    fun clear() {
        cache.clear()
        prefs.edit().clear().apply()
    }

    private fun saveToPrefs() {
        val stored = cache.map { StoredCookie.from(it) }
        val json = gson.toJson(stored)
        prefs.edit().putString(KEY_COOKIE_STORE, json).apply()
    }

    private fun loadFromPrefs() {
        cache.clear()
        val json = prefs.getString(KEY_COOKIE_STORE, null) ?: return
        val type = object : TypeToken<List<StoredCookie>>() {}.type
        val stored = gson.fromJson<List<StoredCookie>>(json, type) ?: emptyList()
        stored.mapNotNull { it.toCookie() }.forEach { cache.add(it) }
    }

    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    ) {
        fun toCookie(): Cookie? {
            if (domain.isBlank()) return null
            return Cookie.Builder()
                .name(name)
                .value(value)
                .apply {
                    if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                }
                .path(path)
                .expiresAt(expiresAt)
                .apply {
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                }
                .build()
        }

        companion object {
            fun from(cookie: Cookie): StoredCookie {
                return StoredCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    expiresAt = cookie.expiresAt,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                )
            }
        }
    }

    companion object {
        private const val KEY_COOKIE_STORE = "cookie_store"
    }
}
