package com.kuanto.webview

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.*

class RateUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kuanto_prefs", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        Log.d("KuantoWorker", "Ejecutando revisión de tasas...")

        // ── Configuración de Zona Horaria (VET: UTC-4) ────────────────
        val timeZone = TimeZone.getTimeZone("GMT-4")
        val calendar = Calendar.getInstance(timeZone)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentDay = calendar.get(Calendar.DAY_OF_YEAR)
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
        }.format(calendar.time)

        // ── 1. Notificación Diaria 1:00 PM (Promedio USDT) ───────────
        if (currentHour == 13) {
            val lastDailyNotifyDay = prefs.getInt("last_daily_notify_day", -1)
            if (lastDailyNotifyDay != currentDay) {
                val p2pData = SupabaseHelper.fetchLatestP2pRate()
                if (p2pData != null) {
                    val price = p2pData.optDouble("price", 0.0)
                    if (price > 0) {
                        NotificationHelper.showNotification(
                            applicationContext,
                            "Promedio USDT hoy",
                            "El promedio actual es de Bs. %.2f".format(price),
                            isDaily = true
                        )
                        prefs.edit().putInt("last_daily_notify_day", currentDay).apply()
                    }
                }
            }
        }

        // ── 2. Alerta BCV (5:00 PM - 7:00 PM) ───────────────────────
        // Solo días de semana (Lunes=2 a Viernes=6)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY && currentHour in 17..19) {
            val bcvData = SupabaseHelper.fetchLatestBcvRate()
            if (bcvData != null) {
                val bcvUsd = bcvData.optDouble("usd", 0.0)
                val bcvDate = bcvData.optString("date", "")
                val lastSavedBcvDate = prefs.getString("last_bcv_date", "")

                // Si la fecha es nueva (ej. ya salió la de mañana) avisamos
                if (bcvDate != "" && bcvDate != lastSavedBcvDate) {
                    NotificationHelper.showNotification(
                        applicationContext,
                        "Nueva Tasa BCV Detectada",
                        "El dólar oficial se ubicó en Bs. %.2f (Fecha: %s)".format(bcvUsd, bcvDate)
                    )
                    prefs.edit().putString("last_bcv_date", bcvDate).apply()
                }
            }
        }

        return Result.success()
    }
}
