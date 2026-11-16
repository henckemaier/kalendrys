package com.kalendrys.app

import com.kalendrys.app.ui.theme.Appearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.withIndex

/**
 * Regra do coletor da Aparência do [KalendrysApp] (ADR-0002, 12.3 e 12.5):
 * - [onEach] em TODA emissão distinta, inclusive a primeira do processo (publicar a aparência e
 *   aplicar o espelho do modo noturno cobrem quem atualizou ou restaurou um backup);
 * - [onChanged] só quando a Aparência MUDA depois disso (redesenhar os widgets). Na primeira emissão
 *   os widgets já estão no esquema certo (cada um lê a Aparência ao compor), e redesenhá-los ali só
 *   disputaria CPU com a abertura do app.
 */
internal suspend fun Flow<Appearance>.collectAppearance(
    onEach: (Appearance) -> Unit,
    onChanged: suspend (Appearance) -> Unit,
) {
    distinctUntilChanged().withIndex().collect { (index, appearance) ->
        onEach(appearance)
        if (index > 0) onChanged(appearance)
    }
}
