# Maizon webOS Emulator 1.3.0 — compatibilidade experimental

## O que o único APK realmente executa

- **Web / HTML5 / WASM:** o IPK é extraído com validação e executado pelo Android System WebView.
- **Híbrido:** a parte HTML é executável; arquivos ELF e serviços nativos externos são identificados, mas não executados pelo WebView.
- **Nativo puro:** o IPK pode ser instalado e listado, e seu ELF pode ser inspecionado. **Ainda não é executado.** Mostra um diagnóstico claro ao tocar, em vez de abrir uma tela preta.

O código Java Android do aplicativo não precisa de `.so` do projeto e pode ser instalado em dispositivos Android ARMv7 e ARM64 que cumpram minSdk 26. Isso **não significa** que arquivos ELF de 32/64 bits do webOS sejam executáveis. Linux/glibc, syscalls, loader, SELinux, GPU, drivers e serviços de sistema são diferentes de Android/Bionic. QEMU não está incluído.

## Tela de TV e organização

O modo **TV 16:9** centraliza o WebView em uma área nativa 16:9 com barras pretas onde necessário, preservando acerto de toque porque não aplica `scaleX/scaleY` visual sem alterar hit-testing.

`TvDisplayMetrics.parseWidth` interpreta `appinfo.json/resolution`, admitindo apenas 1280x720 ou 1920x1080. O script `compat-viewport.js` injetado na resposta HTML usa `data-tv-width` para corrigir o viewport CSS e permitir autoajuste com `WebView.setInitialScale(0)`. O app original continua intacto no disco.

O modo TV vem habilitado para outros IPKs por padrão, **exceto o MaizoSNES webOS**, cujo visual e pipeline WebGL/WASM já foram validados anteriormente. Use o botão **TV** na barra superior ou **Perfil → Tela TV 16:9** para testar e ativar no MaizoSNES; pode desativar a qualquer momento. O perfil LG legado mantém UA e viewport no comportamento anterior.

Quando o modo TV está ativo, o User-Agent LG também pode influenciar media queries e detecção de plataforma; isso não torna a TV real e pode ser revertido com o toggle.

## Instalação e identificação de código nativo

O detector de IPK é passivo e não executa nem carrega bibliotecas. Lê cabeçalho ELF e `e_machine` para rotular ARM, AArch64, x86, x86_64, MIPS etc., com limite de 2.048 entradas por pacote para não travar a interface.

- Entrada `.html`/`.htm` sem ELF: **Web**.
- Entrada HTML + ELF ou tipo nativo declarado: **Híbrido**; exibe aviso de recursos nativos não suportados.
- Entrada sem HTML: **Nativo**; o instalador aceita se houver executável ELF no pacote e apresenta diálogo explicativo ao tocar.

Alguns IPKs possuem bibliotecas, scripts e recursos fora da pasta da aplicação identificada por `appinfo.json`; a implementação existente instala a pasta da aplicação web apenas e não executa serviços genéricos. Não alegamos compatibilidade ampla com esses layouts.

## RetroArch webOS

O RetroArch nativo para webOS pode depender de um executável Linux e bibliotecas não disponíveis no Android. Identificar um ELF e sua ABI **não basta** para abrir a interface, renderizar frames, obter áudio nem mapear gamepad. Esta versão ainda **não roda RetroArch nativo**. O próximo marco exige um engine Linux/ARM (ou ARM32 em ARM64 via tradução) com licenças válidas e teste com binário controlado.

Não integre diretamente ELF fornecido pelo usuário ao processo principal Android. Use isolamento e confirmação do usuário após definir um backend de execução auditável.

## Testes e honestidade

O CI valida código Node/JS, testes de protocolos LAN e inspeção ELF ARMv7/ARM64, geometria 16:9, injeção de viewport 720p/1080p, compila debug/release unsigned, inspeciona APK e publica APK debug instalável.

Uma compilação verde **não valida a aparência na TV real**, nem o RetroArch em execução. Estes testes dependem de um IPK de teste e aparelho físico.

## Roteiro recomendado de validação

1. Instale o novo APK sobre a mesma assinatura debug anterior quando compatível. Faça backup se precisar desinstalar por conflito de assinatura.
2. Importe um IPK HTML webOS e abra no modo TV 16:9; confirme botões visíveis, toque e rolagem correta. Compare o botão TV ON/OFF.
3. Importe um IPK com `appinfo.json.resolution="1280x720"`; confirme viewport declarado e visual sem distorção.
4. Abra o MaizoSNES webOS: por padrão permanece com o modo visual anterior. Teste WebGL, save state, WSS e LAN em dois celulares sem alterar o núcleo.
5. Importe um IPK com ELF nativo: deve constar na biblioteca com ABI e abrir diagnóstico claro, não jogo ou tela preta.
6. Abra **Logs** e exporte detalhes se houver erro. Informe modelo do celular, versão Android e IPK usado.

**Política de releases:** 1 APK debug assinado para testes públicos; release unsigned é compilação não instalável. APK de produção requer uma chave de assinatura privada constante, não incluída no código público.
