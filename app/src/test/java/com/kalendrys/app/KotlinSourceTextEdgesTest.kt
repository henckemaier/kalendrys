package com.kalendrys.app

import com.kalendrys.app.testutil.KotlinSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O leitor de fonte das varreduras do relógio ([KotlinSourceText], v1.10.0 T3; o buraco das strings
 * apontado na E0) nos casos de borda: o que é texto some, o que é código fica, e as linhas não se movem.
 * "LEITURA" marca o código que precisa continuar visível.
 */
class KotlinSourceTextEdgesTest {

    private fun code(src: String) = KotlinSourceText.codeOnly(src)

    private fun assertKeeps(src: String) = assertTrue("o código sumiu em: $src\n-> ${code(src)}", code(src).contains("LEITURA"))

    private fun assertHides(src: String) = assertFalse("texto virou código em: $src\n-> ${code(src)}", code(src).contains("LEITURA"))

    @Test
    fun `o que abre comentario dentro de texto nao esconde o codigo seguinte`() {
        assertKeeps("val a = \"image/" + "*\"\nval b = LEITURA()\n/* fim */")
        assertKeeps("val u = \"https://kalendrys\"; val b = LEITURA()")
        assertKeeps("val r = \"\"\"a // b /" + "* c\"\"\"; LEITURA()")
        assertKeeps("val c = '\"'; LEITURA(); val d = \"x\"")
        assertKeeps("val c = '\\''; LEITURA()")
        assertKeeps("val c = '\\u0041'; LEITURA()")
        assertKeeps("val s = \"a\\\"b\"; LEITURA()")
        assertKeeps("val s = \"a\\\\\"; LEITURA()")
    }

    @Test
    fun `template com codigo continua codigo, inclusive com chaves e strings dentro`() {
        assertKeeps("val s = \"x-\${LEITURA()}\"")
        assertKeeps("val s = \"\${listOf(1).map { LEITURA(it) }}\"")
        assertKeeps("val s = \"\${\"}\"}\${LEITURA()}\"")
        assertKeeps("val s = \"\"\"a \${LEITURA()} b\"\"\"")
    }

    @Test
    fun `texto e comentarios somem, inclusive comentario aninhado e string crua com aspas no fim`() {
        assertHides("val s = \"LEITURA()\"")
        assertHides("val s = \"\"\"\nLEITURA()\n\"\"\"")
        assertHides("// LEITURA()")
        assertHides("/* a /* b */ LEITURA() */")
        assertHides("val s = \"\$LEITURA\"")
        assertKeeps("val s = \"\"\"a\"\"\"\" + LEITURA()")
        assertKeeps("/* a /* b */ c */ LEITURA()")
    }

    @Test
    fun `as quebras de linha ficam onde estavam`() {
        val src = "val a = \"\"\"\num\ndois\n\"\"\"\n/* x\ny */\nLEITURA()\n"
        val out = code(src)
        assertEquals(src.count { it == '\n' }, out.count { it == '\n' })
        assertEquals(src.lines().indexOfFirst { "LEITURA" in it }, out.lines().indexOfFirst { "LEITURA" in it })
        assertEquals(src.length, out.length)
    }
}
