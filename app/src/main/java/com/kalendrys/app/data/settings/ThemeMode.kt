package com.kalendrys.app.data.settings

/**
 * Tema escolhido em Aparência (ADR-0002, 12.3). Guardado (DataStore e backup) pelo NOME do enum:
 * renomear um valor quebra o que já está gravado.
 */
enum class ThemeMode {
    /** Segue o tema claro/escuro do aparelho. Padrão, inclusive para quem atualiza. */
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        /** O valor com esse nome exato, ou `null` se o nome for desconhecido (ou ausente). */
        fun fromNameOrNull(name: String?): ThemeMode? = entries.firstOrNull { it.name == name }
    }
}
