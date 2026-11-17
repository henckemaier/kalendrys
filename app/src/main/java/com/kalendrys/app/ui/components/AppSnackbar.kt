package com.kalendrys.app.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Mensagens do APP, que nascem numa tela e aparecem em outra (v1.8.0, E0-D: o Desfazer da exclusão
 * pelo detalhe, mostrado na tela de origem depois que o detalhe fecha). Quem fornece é o
 * `KalendrysNavHost`, único dono do estado e único que chama [showUndo] nele.
 *
 * Quem desenha é o [AppScaffold] da tela que está à frente (entrada de navegação RESUMED), no slot de
 * mensagens da própria tela, ACIMA da mensagem da tela (nunca no mesmo lugar); como em toda tela do
 * Material3, o slot fica acima do FAB, sem cobri-lo. Por isso TODA tela de destino da navegação tem
 * de usar o [AppScaffold]: numa tela sem ele a mensagem do app não aparece (e o Desfazer some em
 * silêncio quando ela vence).
 *
 * Padrão nulo: sem fornecer (prévias, testes de componente), o [AppScaffold] não desenha nada a mais.
 *
 * Só para essa mensagem: não pendurar outras coisas aqui.
 */
internal val LocalAppSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }
