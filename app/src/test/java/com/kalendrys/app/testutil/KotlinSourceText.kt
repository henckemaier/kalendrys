package com.kalendrys.app.testutil

/**
 * O texto de um arquivo Kotlin/Java só com o CÓDIGO, para as varreduras de fonte (SourceClockReadsTest e
 * SourceClockReadsGapsTest; buraco apontado pelo Lince na E0 da v1.10.0): comentários e o conteúdo
 * literal das strings viram espaço, as quebras de linha ficam (os números de linha continuam valendo).
 *
 * Por que um leitor e não duas regex: com regex, um "/" seguido de "*" dentro de string abria um falso
 * comentário que escondia o código até o próximo fechamento, e o "//" de uma URL apagava o resto da
 * linha. Aqui as strings são lidas ANTES, então nada dentro delas abre comentário.
 *
 * O código de dentro de um template (`${...}`) CONTINUA: uma leitura do relógio escondida num template
 * ("x-${LocalDate.now()}") seria código de verdade. `$nome` simples não é chamada e vira espaço.
 *
 * Cobre: comentário de linha e de bloco (aninhado, como no Kotlin), string "..." com escapes, string
 * crua com três aspas, char literal ('x', '\n', '"').
 */
object KotlinSourceText {

    fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        Reader(text, out).code(stopAtBrace = false)
        return out.toString()
    }

    private class Reader(val s: String, val out: StringBuilder) {
        var i = 0

        private fun blank(c: Char) {
            out.append(if (c == '\n') '\n' else ' ')
        }

        /** Código até o fim (ou até o `}` que fecha um template, quando [stopAtBrace]). */
        fun code(stopAtBrace: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '/' && peek(1) == '/' -> lineComment()
                    c == '/' && peek(1) == '*' -> blockComment()
                    c == '"' && peek(1) == '"' && peek(2) == '"' -> rawString()
                    c == '"' -> string()
                    c == '\'' -> charLiteral()
                    stopAtBrace && c == '{' -> { depth++; out.append(c); i++ }
                    stopAtBrace && c == '}' -> {
                        if (depth == 0) return
                        depth--; out.append(c); i++
                    }
                    else -> { out.append(c); i++ }
                }
            }
        }

        private fun peek(k: Int): Char? = s.getOrNull(i + k)

        private fun lineComment() {
            while (i < s.length && s[i] != '\n') { blank(s[i]); i++ }
        }

        private fun blockComment() {
            var depth = 0
            while (i < s.length) {
                if (s[i] == '/' && peek(1) == '*') {
                    depth++; blank(s[i]); blank(s[i + 1]); i += 2
                } else if (s[i] == '*' && peek(1) == '/') {
                    depth--; blank(s[i]); blank(s[i + 1]); i += 2
                    if (depth == 0) return
                } else {
                    blank(s[i]); i++
                }
            }
        }

        /** Template `${...}` dentro de string: o código fica; as chaves viram espaço. */
        private fun template() {
            blank(s[i]); blank(s[i + 1]); i += 2
            code(stopAtBrace = true)
            if (i < s.length) { blank(s[i]); i++ }
        }

        private fun string() {
            blank(s[i]); i++
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> { blank(c); i++; if (i < s.length) { blank(s[i]); i++ } }
                    c == '$' && peek(1) == '{' -> template()
                    c == '"' -> { blank(c); i++; return }
                    c == '\n' -> return // string que não fechou na linha: devolve ao código
                    else -> { blank(c); i++ }
                }
            }
        }

        private fun rawString() {
            repeat(3) { blank(s[i]); i++ }
            while (i < s.length) {
                when {
                    s[i] == '"' && peek(1) == '"' && peek(2) == '"' -> {
                        // Aspas a mais no fim fazem parte do conteúdo ("""a"""" termina com a").
                        while (peek(3) == '"') { blank(s[i]); i++ }
                        repeat(3) { blank(s[i]); i++ }
                        return
                    }
                    s[i] == '$' && peek(1) == '{' -> template()
                    else -> { blank(s[i]); i++ }
                }
            }
        }

        private fun charLiteral() {
            blank(s[i]); i++
            if (i < s.length && s[i] == '\\') {
                blank(s[i]); i++
                // Escape unicode ("\" + "u" + quatro dígitos): o u e os quatro dígitos.
                if (i < s.length && s[i] == 'u') repeat(4) { if (i + 1 < s.length) { blank(s[i]); i++ } }
            }
            if (i < s.length) { blank(s[i]); i++ }
            if (i < s.length && s[i] == '\'') { blank(s[i]); i++ }
        }
    }
}
