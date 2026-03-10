package com.kuanto.webview

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object SupabaseHelper {
    private const val SUPABASE_URL = "https://goiaxsdsrwxlebpsnbrx.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdvaWF4c2Rzcnd4bGVicHNuYnJ4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjgyMzE4NTksImV4cCI6MjA4MzgwNzg1OX0.iW5CRoQ_gjvEqxUXkiHPxSpL8kWSdXzdQZmqyMt167I"
    
    private val client = OkHttpClient()

    fun fetchLatestBcvRate(): JSONObject? {
        val url = "$SUPABASE_URL/rest/v1/bcv_rates_history?select=usd,eur,date&order=date.desc&limit=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val array = JSONArray(body)
                if (array.length() > 0) array.getJSONObject(0) else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun fetchLatestP2pRate(): JSONObject? {
        val url = "$SUPABASE_URL/rest/v1/p2p_rate_history?select=price,created_at&order=created_at.desc&limit=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val array = JSONArray(body)
                if (array.length() > 0) array.getJSONObject(0) else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
