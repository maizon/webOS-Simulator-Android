# Maizon webOS Emulator — 1.4.0 (Linux ELF ARM32/ARM64 experimental)

Projeto independente da comunidade **Maizon**: um único APK universal Android ARM32/ARM64 que instala IPKs, executa apps HTML5/JavaScript/WebAssembly e inclui um **motor Linux ELF experimental** com PRoot e Debian para executar programas nativos de terminal quando a ABI e dependências forem compatíveis. Perfil de referência: LG UQ8050PSB / webOS TV 22.

> **AVISO:** não é firmware LG. O PRoot executa Linux ELF experimental **sem janela gráfica nativa, GPU, áudio, SDL ou QEMU-user**; RetroArch e outros jogos Linux gráficos **não estão comprovados nem disponíveis em gameplay**. O código ELF roda sob o mesmo UID e permissões do nosso app; execute apenas IPKs confiáveis. WebView continua intacto. Nenhuma porcentagem de compatibilidade é garantida.

O conteúdo original dos IPKs é preservado: correções de HTTP/JavaScript/viewport são aplicadas pelo servidor durante o carregamento, sem modificar os arquivos de origem. O repositório MaizoSNES Android não é alterado.

## Novidades 1.4.0 — motor Linux ARMv7 + ARM64 no mesmo APK

- **PRoot compilado para duas ABIs Android** com Android NDK: \`armeabi-v7a\` e \`arm64-v8a\`. Os executáveis host e seus loaders são incluídos em \`lib/\` dentro de **um único APK**.
- **Duas sysroots Debian GNU/Linux oficiais** (armhf e arm64), pequenas, distribuídas dentro do APK com libc6, BusyBox estático, bibliotecas C/C++ básicas e um executável Linux ELF de teste. Nenhuma instalação de Termux e nenhuma conexão adicional após instalar o APK.
- **Execução Linux ELF experimental de terminal**: na biblioteca toque em **▶ ELF**, leia o aviso de acesso aos dados do aplicativo, escolha **Testar motor** (programa controlado) ou **Executar ELF** (programa importado). Saída e código de retorno aparecem em diagnóstico.
- **Preservação do conteúdo nativo dos IPKs** fora da pasta \`appinfo.json\`, incluindo arquivos \`usr/lib\`, dentro de um sandbox de arquivos próprio. PRoot os apresenta à aplicação, mas NÃO oferece isolamento de segurança contra código malicioso.
- **Configurações → Testar execução Linux ELF (PRoot)** valida o motor independente de qualquer IPK de terceiros.
- **CI testa** compilação Android ARM32/ARM64, criação dos dois ambientes Linux, executa os dois executáveis hóspedes via QEMU no runner, JUnit, testes JavaScript e inspeção do APK final.
- **Ainda não incluído:** QEMU-user Android para executar arquitetura cruzada; Wayland/X11, SDL, GPU, áudio Linux, compatibilidade completa com webOS nativo ou RetroArch. Não há promessa de jogos Linux gráficos "lisos" neste estágio.

📄 [Arquitetura, licenças, desempenho e validação em aparelho](docs/LINUX_NATIVE_RUNTIME.md).

## Novidades — versão 1.3.0 (um APK universal)

**Importe um IPK pela biblioteca e toque para abrir, quando for um app web compatível.** O motor web é o Android WebView. Esta versão aprimora a aparência dos aplicativos de TV e a identificação de arquivos ELF Linux:

- **Tela TV centralizada 16:9**, sem esticar a imagem e sem desalinhar toques: `TvAspectLayout` com letterboxing de hardware. Resolução lógica definida pelo `appinfo.json` (1920×1080 ou 1280×720). O viewport não força mais `minWidth=1920px` ou `initial-scale=1`, que causavam páginas cortadas em celulares.
- **Botão TV** durante execução e opção por aplicativo no menu **⚙ Perfil**. O modo TV fica habilitado por padrão para novos apps, **exceto o MaizoSNES webOS**, preservando seu comportamento WebGL já validado.
- **Reconhecimento automático de aplicativos web, híbridos e nativos**: inspeciona a assinatura ELF real e mostra arquitetura ARM32/ARM64/x86 etc. Aplicativos puramente nativos podem ser importados para análise, mas, ao abrir, apresentam **diagnóstico** em vez de tela preta.
- **APK universal de aplicação Java**, sem filtros ABI e sem bibliotecas JNI nativas: instalável em dispositivos Android ARMv7/ARM64 compatíveis com minSdk 26. A execução de ELF Linux ainda não existe.
- **Testes novos** de cabeçalhos ELF, classificação IPK, geometria 16:9, resoluções 720p/1080p e comportamento do script de viewport.

**O RetroArch webOS nativo ainda NÃO possui interface/áudio/gameplay executável.** Uma camada gráfica Linux com SDL e QEMU-user com bibliotecas, framebuffer/áudio/gamepad e isolamento de processo depende de desenvolvimento e validação adicional. O APK não contém QEMU, rootfs Linux nem firmware LG, e o modo híbrido não executa serviços Node.js genéricos.

📄 [Diagnóstico/arquitetura e testes](docs/EMULATOR_COMPATIBILITY.md) · [Prompt técnico completo para evolução autônoma](PROMPT_EMULADOR_WEBOS_UNIVERSAL.md)

## Controle MaizoSNES Android como gamepad LAN — 1.2.0

**Agora o próprio Android executa os sockets nativos para o serviço `com.maizon.maizosnes.webos.bridge`** quando o IPK `com.maizon.maizosnes.webos` solicita `luna://com.maizon.maizosnes.webos.bridge/open`. Em vez de alegar que executa o serviço Node.js de uma TV LG, o simulador implementa o mesmo protocolo de rede em **Java Android**:

- **55437/UDP**: gamepad MaizoSNES Android MZRC/1, HELLO → oferta ao anfitrião → autorização explícita → ACCEPT com token/slot P2–P4 → INPUT de botões, PING/PONG e timeout de liberação automática.
- **55436/UDP**: anúncios LAN MZSCAN2/MZSCAN3, MZOFFER2/MZOFFER3, descoberta automática por broadcast.
- **55435/TCP**: transporte binário MZNP para Android ↔ IPK webOS, com o handshake normal dos aplicativos. **Não promete GekkoNet UDP cruzado**: os próprios clientes negociam o fallback.
- **WebSocket localhost**: interface `ws://127.0.0.1:porta/bridge` compatível com `webos/native-bridge.js`, autenticação por segredo de 192 bits, filtro de Origin do IPK aberto, quadros limitados e servidor restrito a localhost.
- **Compatibilidade do IPK sem alteração**: ao abrir *este IPK*, o simulador adota o User-Agent LG para que a verificação conservadora de `navigator.userAgent` no `native-bridge.js` não bloqueie capacidades UDP/TCP realmente disponíveis no Android. Outros IPKs mantêm seus perfis originais.
- **Ciclo de vida**: LAN desativada ao fechar/trocar IPK, sair do simulador ou ao desconectar o WebSocket; nenhuma porta LAN é aberta antes da autenticação e do comando do jogo.
- **Segurança**: somente endereços IPv4 privados/loopback, até três controles, confirmação do host para cada novo controle, limites de payload, sequências monotônicas, tokens, timeouts e bloqueio de mensagens sem autenticação. Não encaminha ROMs sem o protocolo MZNP original.
- **Fallbacks intactos**: multiplayer Railway WSS, WebRTC/GekkoNet, biblioteca, áudio, WebGL, save state e ROMs continuam sob controle do próprio MaizoSNES.

O IPK que já está instalado pode ser reutilizado; **não precisa reinstalar** se ele já possui `webos/native-bridge.js`. Para usar, abra uma ROM no MaizoSNES IPK; na interface do IPK habilite LAN/controle Android, que normalmente vem ativado. O celular com o APK MaizoSNES Android deve estar na mesma rede e solicitar controle LAN. O IPK solicita aprovação para P2, P3 ou P4. Use **⋮ → Diagnóstico LAN UDP/TCP (MaizoSNES)** para conferir se a porta abriu. Em **Configurações → LAN nativa MaizoSNES** você pode desabilitar totalmente o adaptador sem alterar o modo WSS.

**Escopo:** não há interpretador Node.js genérico, nem execução de `require('dgram')`/`require('net')` de outros serviços Node.js empacotados. O serviço específico do MaizoSNES é reproduzido por um adaptador Java interoperável. Sockets no dispositivo físico e descobertas na rede Wi-Fi ainda exigem teste com dois aparelhos.

Veja [documentação do protocolo LAN](docs/MAIZO_LAN_ADAPTER.md) e [matriz Luna](docs/LUNA_API_MATRIX.md).

## Luna / webOS TV 22 — v1.1.0

**Nova camada de compatibilidade webOS/Luna**, baseada nas referências públicas da LG: `webOS.service.request`, `PalmServiceBridge`, `webOSDev`, `webOS.deviceInfo(callback)`, `fetchAppId`, `fetchAppInfo`, `webOSSystem` e serviços simulados de rede, horário, idioma, informações da TV, áudio virtual, app manager e banco privado por IPK. Suporta callbacks `onSuccess`, `onFailure`, `onComplete`, `subscribe` (sondagem limitada) e `cancel`.

Na tela do IPK: **⋮ → Teste de APIs Luna webOS 22**. Resultados indicam `ANDROID_NETWORK`, `ANDROID_CLOCK`, `TV_PROFILE`, `VIRTUAL_AUDIO`, `APP_PRIVATE_DB`, `MOCK` ou `UNSUPPORTED`. Chamadas a DRM, LGUDID, Keymanager, BLE, câmera, serviços nativos e recursos proprietários retornam falha explícita. **Não há suporte a todas as APIs Luna de uma Smart TV real, nem firmware LG ou validação ACG**.

[**Matriz detalhada das APIs e limitações**](docs/LUNA_API_MATRIX.md).

**Release v1.1.0**: APK debug instalável publicado separadamente da versão 1.2.0, para preservar uma cópia estável. O MaizoSNES webOS e o MaizoSNES Android originais não foram modificados neste repositório.

## Baixar e instalar APK

Abra **Actions → Android APK → última execução verde → Artifacts → webos-simulator-android-apks → app-debug.apk**.

- app-debug.apk: APK Android instalável, assinado pela chave debug da execução do CI. **Contém PRoot nativo e sysroots Linux nas duas ABIs** ARMv7/ARM64 e Java/WebView; Android minSdk 26. O APK não é universal x86 neste estágio.
- app-release-unsigned.apk: build Release **sem assinatura**, não instalável diretamente.
- A Action publica o APK debug instalável como pré-release **v1.4.0** no GitHub Releases. Isso cria uma segunda cópia, fora dos Artifacts, quando as permissões permitem.
- A assinatura da build debug pode variar entre execuções CI. Se o Android rejeitar a atualização por assinatura incompatível, exporte dados antes de desinstalar a versão anterior.

## Funcionalidades

| Camada | Implementação |
|---|---|
| Instalação IPK | SAF Android, Debian-ar, data.tar, data.tar.gz, data.tar.xz e data.tar.bz2, com limites de tamanho, rejeição de symlinks e proteção contra traversal; análise passiva ELF ARM/ARM64 |
| HTTP localhost | GET/HEAD/OPTIONS, streaming, HTTP Range 206/416, caminhos absolutos /assets, MIME WASM/JS/áudio/vídeo, porta estável por IPK quando possível |
| Perfis por IPK | Balanceado, LG legado e WASM threads; alternar TV 16:9 no app, viewport 720p/1080p por manifest; reinicia apenas o IPK |
| Web | JavaScript, DOM storage, IndexedDB, WebGL, WASM, WebAudio e WebRTC dentro dos limites reais do Android WebView |
| APIs webOS | webOS, webOSSystem, service.request, PalmServiceBridge, informações do app, mocks Luna marcados MOCK e falhas UNSUPPORTED |
| Gráficos | Aceleração do WebView Android, teste independente Canvas2D/WebGL1/WebGL2, relatório de erros e tamanho/visibilidade dos Canvas |
| Diagnóstico | Erros HTTP/console/JS, viewport, capacidades WASM/Workers, contexto WebGL perdido, rAF medido sob demanda (não FPS real do jogo) |
| Controles | Remoto LG sintético, touchpad, teclas de gamepad, zoom individual e modo imersivo (Back restaura barras) |
| Estabilidade | Log de crash do renderer, tratamento de permissões, arquivos locais do WebView bloqueados e seletor SAF para ROMs |
| Linux ELF | PRoot Android ARMv7/ARM64, Debian armhf/arm64, executor de terminal experimental por confirmação, logs e interrupção; sem GUI Linux |
| CI | SDK 35, NDK 29, clang bare Linux ELF, QEMU estático no runner, JDK 17, JUnit/Node, APK universal debug + unsigned release |

### Perfis de compatibilidade

**Balanceado (padrão):** mantém User-Agent real do Android, não injeta COOP/COEP. Primeiro perfil a testar para HTML5 e recursos carregados de CDN.

**LG legado:** User-Agent semelhante ao da LG e viewport lógico 1920x1080. Ajuda aplicativos que exigem layout de TV, mas não substitui navegador/driver LG.

**WASM threads:** cabeçalhos COOP/COEP e viewport 1080p. Necessário para certos aplicativos com SharedArrayBuffer, mas pode impedir carregamento de recursos externos e não obriga o WebView a oferecer threads.

Na biblioteca, use **⚙ Perfil** no IPK. Durante execução, abra **⋮ → Perfil de compatibilidade**. O menu **Configurações** controla perfil padrão, paisagem, tela ligada e padrão da tela TV; o botão **TV** alterna o modo de apresentação 16:9 por aplicativo. A opção TV não altera arquivos IPK no disco.

### Diagnóstico de tela preta

1. Abra o IPK e observe se a interface aparece, se há áudio e em que momento a tela fica preta.
2. Use **⋮ → Teste GPU independente**. Exibe quadro Canvas2D e triângulos desenhados por WebGL1/WebGL2. WebGL2 pode não estar disponível em aparelhos antigos.
3. Clique em **GPU** na tela do IPK para coletar relatório: Canvas DOM, dimensões CSS, erros, capacidades WASM, perda de contexto, tempos de recursos e frequência do rAF (não FPS do jogo).
4. Use **Logs** ou **⋮ → Exportar logs** e observe HTTP_404, WEB_ERROR, GRAPHICS, JS_ERROR, CONSOLE e UNSUPPORTED.
5. Compare perfis Balanceado, LG legado e WASM threads. Se o isolamento impedir recursos remotos, retorne a Balanceado.

**Histórico:** o MaizoSNES webOS já teve tela preta corrigida no projeto próprio via WebGL RGB565. RetroArch nativo agora tem diagnóstico de ELF, porém **continua sem runtime nativo**; IPKs web como Fruit Ninja ainda exigem teste físico.

Um CI verde **não comprova** que essas telas pretas estejam resolvidas. É preciso testar IPKs reais em Android e compartilhar os relatórios.

## Segurança, limites e comportamento

- Os arquivos são atendidos somente pelo servidor loopback (127.0.0.1). Recursos iniciados por caminhos absolutos são resolvidos dentro do único IPK ativo, não no sistema de arquivos Android.
- HTTP Range suporta intervalo único, aberto e sufixo; múltiplos intervalos são rejeitados explicitamente.
- Cabeçalhos COOP/COEP são opcionais, específicos do perfil. HTML recebe scripts de compatibilidade antes dos scripts do jogo sem escrever sobre os arquivos instalados.
- CSP, Service Workers, navegação especial, media/protocolo proprietários e apps com dependência forte da GPU de TV podem continuar falhando.
- Serviços Luna não suportados retornam erro explícito. Não falsificamos suporte a WebGL, SharedArrayBuffer, DRM ou hardware.
- O WebView não recebe permissões de microfone/câmera automaticamente. Aplicativos de terceiros podem fazer requisições web e executar scripts; **instale somente IPKs confiáveis**.
- data.tar.zst, integração gráfica/áudio de **código nativo Linux/webOS**, QEMU-user, DRM/EME específicos da LG, serviços proprietários e aplicativos protegidos de streaming **não são suportados**. Execução de ELF terminal é experimental.
- Apps podem importar arquivos/ROMs pelo seletor Android SAF quando possuem um elemento de seleção web. Não distribuímos jogos nem ROMs.

## Desenvolvimento

Requer JDK 17, Gradle 8.7, Android SDK 35 e minSdk 26.

    # Instalar Android NDK 29; CI baixa QEMU-user-static Linux x86_64 para testes
    bash tools/build-native-runtime.sh
    bash tools/build-linux-sysroots.sh
    gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
    node --check app/src/main/assets/webos-shim.js
    node --check app/src/main/assets/graphics-doctor.js
    node --check app/src/main/assets/compat-viewport.js
    node --test app/src/test/js/tv-viewport.test.cjs

Workflow: .github/workflows/android.yml.

**Comprovado pelo CI:** código compila, testes JUnit/JS passam, APKs são produzidos.

**Não comprovado sem hardware/IPK real:** PRoot Linux em Android físico, RetroArch nativo (GUI/áudio não integrados), Fruit Ninja, MaizoSNES nesta nova versão, ROMs, LAN/multiplayer, áudio contínuo e apresentação correta de frames.

Projeto independente Maizon; sem vínculo com LG. Não inclui firmware LG, DRM, ROMs ou bibliotecas proprietárias.
