# webOS IPK Simulator Android — 1.1.0 (experimental)

Projeto independente da comunidade **Maizon** para executar aplicativos web empacotados como IPK de webOS no Android. Perfil de referência: LG UQ8050PSB / webOS TV 22.

> **AVISO:** não é firmware LG, não executa serviços nativos webOS, bibliotecas proprietárias, DRM nem binários nativos de IPKs. Usa Android System WebView. Compatibilidade de 99,9% não foi demonstrada e não pode ser garantida para todos os IPKs.

O IPK instalado permanece original: correções de HTTP/JavaScript/viewport são aplicadas pelo servidor durante o carregamento, sem modificar os arquivos de origem. O repositório MaizoSNES Android não é alterado.

## Luna / webOS TV 22 — v1.1.0

**Nova camada de compatibilidade webOS/Luna**, baseada nas referências públicas da LG: `webOS.service.request`, `PalmServiceBridge`, `webOSDev`, `webOS.deviceInfo(callback)`, `fetchAppId`, `fetchAppInfo`, `webOSSystem` e serviços simulados de rede, horário, idioma, informações da TV, áudio virtual, app manager e banco privado por IPK. Suporta callbacks `onSuccess`, `onFailure`, `onComplete`, `subscribe` (sondagem limitada) e `cancel`.

Na tela do IPK: **⋮ → Teste de APIs Luna webOS 22**. Resultados indicam `ANDROID_NETWORK`, `ANDROID_CLOCK`, `TV_PROFILE`, `VIRTUAL_AUDIO`, `APP_PRIVATE_DB`, `MOCK` ou `UNSUPPORTED`. Chamadas a DRM, LGUDID, Keymanager, BLE, câmera, serviços nativos e recursos proprietários retornam falha explícita. **Não há suporte a todas as APIs Luna de uma Smart TV real, nem firmware LG ou validação ACG**.

[**Matriz detalhada das APIs e limitações**](docs/LUNA_API_MATRIX.md).

**Release v1.1.0**: APK debug instalável publicado separadamente da versão 1.1.0, para preservar uma cópia estável. O MaizoSNES webOS e o MaizoSNES Android originais não foram modificados neste repositório.

## Baixar e instalar APK

Abra **Actions → Android APK → última execução verde → Artifacts → webos-simulator-android-apks → app-debug.apk**.

- app-debug.apk: APK Android instalável, assinado pela chave debug da execução do CI. É um APK Java universal, sem bibliotecas JNI do projeto, para Android ARMv7 e ARM64 compatíveis com minSdk 26.
- app-release-unsigned.apk: build Release **sem assinatura**, não instalável diretamente.
- A Action tenta publicar o APK debug instalável como pré-release **v1.1.0** no GitHub Releases. Isso cria uma segunda cópia, fora dos Artifacts, quando as permissões permitem.
- A assinatura da build debug pode variar entre execuções CI. Se o Android rejeitar a atualização por assinatura incompatível, exporte dados antes de desinstalar a versão anterior.

## Funcionalidades

| Camada | Implementação |
|---|---|
| Instalação IPK | SAF Android, Debian-ar, data.tar, data.tar.gz, data.tar.xz e data.tar.bz2, com limites de tamanho, rejeição de symlinks e proteção contra traversal |
| HTTP localhost | GET/HEAD/OPTIONS, streaming, HTTP Range 206/416, caminhos absolutos /assets, MIME WASM/JS/áudio/vídeo, porta estável por IPK quando possível |
| Perfis por IPK | Balanceado, LG legado 1080p e WASM threads; mudança reinicia apenas o IPK |
| Web | JavaScript, DOM storage, IndexedDB, WebGL, WASM, WebAudio e WebRTC dentro dos limites reais do Android WebView |
| APIs webOS | webOS, webOSSystem, service.request, PalmServiceBridge, informações do app, mocks Luna marcados MOCK e falhas UNSUPPORTED |
| Gráficos | Aceleração do WebView Android, teste independente Canvas2D/WebGL1/WebGL2, relatório de erros e tamanho/visibilidade dos Canvas |
| Diagnóstico | Erros HTTP/console/JS, viewport, capacidades WASM/Workers, contexto WebGL perdido, rAF medido sob demanda (não FPS real do jogo) |
| Controles | Remoto LG sintético, touchpad, teclas de gamepad, zoom individual e modo imersivo (Back restaura barras) |
| Estabilidade | Log de crash do renderer, tratamento de permissões, arquivos locais do WebView bloqueados e seletor SAF para ROMs |
| CI | SDK 35, JDK 17, JUnit, validação JS, APK debug e unsigned release |

### Perfis de compatibilidade

**Balanceado (padrão):** mantém User-Agent real do Android, não injeta COOP/COEP. Primeiro perfil a testar para HTML5 e recursos carregados de CDN.

**LG legado:** User-Agent semelhante ao da LG e viewport lógico 1920x1080. Ajuda aplicativos que exigem layout de TV, mas não substitui navegador/driver LG.

**WASM threads:** cabeçalhos COOP/COEP e viewport 1080p. Necessário para certos aplicativos com SharedArrayBuffer, mas pode impedir carregamento de recursos externos e não obriga o WebView a oferecer threads.

Na biblioteca, use **⚙ Perfil** no IPK. Durante execução, abra **⋮ → Perfil de compatibilidade**. O menu **Configurações** controla perfil padrão, paisagem e tela ligada.

### Diagnóstico de tela preta

1. Abra o IPK e observe se a interface aparece, se há áudio e em que momento a tela fica preta.
2. Use **⋮ → Teste GPU independente**. Exibe quadro Canvas2D e triângulos desenhados por WebGL1/WebGL2. WebGL2 pode não estar disponível em aparelhos antigos.
3. Clique em **GPU** na tela do IPK para coletar relatório: Canvas DOM, dimensões CSS, erros, capacidades WASM, perda de contexto, tempos de recursos e frequência do rAF (não FPS do jogo).
4. Use **Logs** ou **⋮ → Exportar logs** e observe HTTP_404, WEB_ERROR, GRAPHICS, JS_ERROR, CONSOLE e UNSUPPORTED.
5. Compare perfis Balanceado, LG legado e WASM threads. Se o isolamento impedir recursos remotos, retorne a Balanceado.

**Relatos do usuário na primeira build, ainda não validados na versão 1.1.0:** MaizoSNES abre interface e produz áudio, mas imagem do jogo fica preta; RetroArch e Fruit Ninja iniciam com tela preta.

Um CI verde **não comprova** que essas telas pretas estejam resolvidas. É preciso testar IPKs reais em Android e compartilhar os relatórios.

## Segurança, limites e comportamento

- Os arquivos são atendidos somente pelo servidor loopback (127.0.0.1). Recursos iniciados por caminhos absolutos são resolvidos dentro do único IPK ativo, não no sistema de arquivos Android.
- HTTP Range suporta intervalo único, aberto e sufixo; múltiplos intervalos são rejeitados explicitamente.
- Cabeçalhos COOP/COEP são opcionais, específicos do perfil. HTML recebe scripts de compatibilidade antes dos scripts do jogo sem escrever sobre os arquivos instalados.
- CSP, Service Workers, navegação especial, media/protocolo proprietários e apps com dependência forte da GPU de TV podem continuar falhando.
- Serviços Luna não suportados retornam erro explícito. Não falsificamos suporte a WebGL, SharedArrayBuffer, DRM ou hardware.
- O WebView não recebe permissões de microfone/câmera automaticamente. Aplicativos de terceiros podem fazer requisições web e executar scripts; **instale somente IPKs confiáveis**.
- data.tar.zst, código nativo webOS, DRM/EME específicos da LG, serviços proprietários e aplicativos protegidos de streaming **não são suportados**.
- Apps podem importar arquivos/ROMs pelo seletor Android SAF quando possuem um elemento de seleção web. Não distribuímos jogos nem ROMs.

## Desenvolvimento

Requer JDK 17, Gradle 8.7, Android SDK 35 e minSdk 26.

    gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
    node --check app/src/main/assets/webos-shim.js
    node --check app/src/main/assets/graphics-doctor.js
    node --check app/src/main/assets/compat-viewport.js

Workflow: .github/workflows/android.yml.

**Comprovado pelo CI:** código compila, testes JUnit/JS passam, APKs são produzidos.

**Não comprovado sem hardware/IPK real:** RetroArch, Fruit Ninja, MaizoSNES, ROMs, LAN/multiplayer, áudio contínuo e apresentação correta de frames.

Projeto independente Maizon; sem vínculo com LG. Não inclui firmware LG, DRM, ROMs ou bibliotecas proprietárias.
