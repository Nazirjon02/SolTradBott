package tj.khujand.solana.trading.bot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tj.khujand.solana.trading.bot.DrxRuntimeHolder

/**
 * Телефон как сервер 24/7: после перезагрузки (пропало питание, обновление системы) и после обновления
 * самого приложения торговля поднимается без человека — если включён тумблер «Возобновлять торговлю при
 * запуске» и бота оставили работающим или на паузе.
 *
 * К моменту onReceive процесс уже поднят и DrxApp.onCreate собрал рантайм. Здесь остаётся запустить
 * foreground-сервис с командой восстановления: из этих системных broadcast'ов запуск сервиса из фона
 * разрешён и на Android 12+ (тип specialUse не входит в запрещённые для BOOT_COMPLETED).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val runtime = DrxRuntimeHolder.get() ?: return
        if (!runtime.takeResumeOnLaunch()) return
        runCatching { ServiceHelper.restoreTrading(context) }
    }
}
