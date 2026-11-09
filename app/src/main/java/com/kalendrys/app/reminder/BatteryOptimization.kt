package com.kalendrys.app.reminder

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

/**
 * O sistema está poupando bateria a ponto de poder adiar os alarmes deste app?
 *
 * `isIgnoringBatteryOptimizations` = true significa que o app está ISENTO da otimização (o caso
 * bom: os alarmes não são segurados). Quando false, em Doze/App Standby alguns fabricantes podem
 * atrasar os disparos, mesmo com alarme exato concedido.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val manager = context.getSystemService(PowerManager::class.java) ?: return true
    return manager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Intent para a lista "Otimização de bateria" do sistema, onde o usuário marca este app como
 * "Não otimizar".
 *
 * Decisão de projeto: usamos este deep-link (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, que
 * abre a tela do sistema) em vez de `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — este exigiria
 * a permissão `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, que atrai escrutínio da Play Store/F-Droid.
 */
fun ignoreBatteryOptimizationsSettingsIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
