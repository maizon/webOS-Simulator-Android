# SUPER PROMPT — webOS Simulator Android: Graphics & TV UX
## Repositório: maizon/webOS-Simulator-Android

Atue como engenheiro Android/WebView/WebGL/WebAssembly e implemente, em um único commit, uma atualização compatível com LG UQ8050PSB/webOS TV 22. O artefato correto é um APK Android universal (não um IPK). IPKs são aplicativos de entrada instalados no simulador.

Objetivo prioritário: diagnosticar e corrigir tela preta com áudio no MaizoSNES webOS sem alterar o IPK ou seu repositório. Não presuma que o defeito é WebGL: audite Canvas 2D, CSS, viewport, framebuffer, shaders, OffscreenCanvas, WebAssembly, requestAnimationFrame, workers, COOP/COEP, renderização do WebView e ciclo de vida.

Requisitos:
1. Graphics Doctor com teste independente Canvas 2D, WebGL1/2, framebuffer, tamanho/visibilidade de canvas, perda de contexto, recursos WebAssembly e logs exportáveis. Medir, não inventar FPS.
2. Simulação visual da LG UQ8050PSB: perfil JSON, viewport lógico 1920×1080, referência 4K física, paisagem, 16:9, zoom 50–300%, ajuste ao celular. Não confundir resolução lógica e resolução real.
3. Controle LG virtual compacto e completo, com teclas, botões coloridos, navegação e controle físico Bluetooth/USB quando suportado pelo Android WebView.
4. Mouse/touchpad virtual, clique, arraste, scroll, ponteiro configurável; usar eventos reais quando possível e identificar limitações dos eventos JS sintéticos.
5. Configurações por aplicativo: zoom, escala, modo compatibilidade, COOP/COEP, diagnóstico, controle e preferências persistentes.
6. Segurança: servidor local loopback, isolamento por IPK, sem firmware LG, sem DRM, sem executar .so nativos, não desativar CORS indiscriminadamente.
7. Compatibilidade: preservar instalação IPK, WebAudio, ROM picker SAF, WebAssembly, Luna mocks, rede WebSocket/WebRTC, logs e biblioteca.
8. Testes: Java unit tests, testes instrumentados WebView/GPU quando houver Android disponível; CI GitHub Actions compilando APK debug instalável e release (assinado apenas com secrets).
9. Evitar opções fictícias como troca arbitrária de backend Chromium ou Canvas2D em aplicativos que dependem de WebGL. Cada opção precisa ter efeito real e status claro.
10. Manter um único commit de implementação, documentar lacunas e entregar APK via artifact do GitHub Actions.

Critérios de aceite: build CI verde, testes passando, Graphics Doctor produz relatório, controles e zoom funcionando. A correção da tela preta e multiplayer MaizoSNES só podem ser considerados validados após testes com IPK real em dispositivo Android. Nunca declarar 100% de compatibilidade sem evidências.
