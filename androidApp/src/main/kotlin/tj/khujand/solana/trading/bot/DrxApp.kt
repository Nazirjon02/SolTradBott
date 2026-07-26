package tj.khujand.solana.trading.bot

import android.app.Application

/**
 * Application-точка входа. Раньше рантайм инициализировался только в MainActivity —
 * поэтому при системном рестарте одного foreground-сервиса (без Activity) DrxRuntimeHolder
 * оставался пустым и торговля не поднималась. Инициализация здесь гарантирует, что рантайм
 * существует всегда, пока жив процесс, — и сервис при рестарте находит живой движок.
 *
 * Порядок важен: initServiceController задаёт appContext, который читает android-драйвер БД
 * внутри DrxRuntimeHolder.init.
 */
class DrxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initServiceController(this)
        DrxRuntimeHolder.init("soltradbot.db")
    }
}
