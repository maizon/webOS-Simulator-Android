# Maizon webOS Emulator 1.4.0 — execução Linux ELF experimental

## Status real (o que é e o que não é)

Esta versão **inclui um motor externo de execução Linux em ARMv7 e ARM64**, baseado em PRoot (GPL-2.0), construído com Android NDK e empacotado **duas vezes no mesmo APK**, nas ABIs `armeabi-v7a` e `arm64-v8a`.

Também inclui duas sysroots pequenas de **Debian GNU/Linux armhf e arm64**, extraídas do APK no primeiro uso e acompanhadas por BusyBox, libc6, libgcc-s1, libstdc++6 e zlib1g. Os pacotes Debian são baixados no CI e verificados contra checksums do índice de pacotes Debian. O executável Linux de teste `/bin/maizon-probe` é compilado via Android NDK clang com syscalls Linux e sem libc, para cada arquitetura, e executado sob QEMU-user-static do Debian (somente no CI).

**Não é uma VM de webOS.** PRoot é um tradutor de caminhos/chamadas de sistema e executor sem root. Não executa shell do Android nem usa Termux. **Não é uma sandbox segura**: código Linux do IPK executa sob o UID do nosso aplicativo, pode acessar dados desse UID e pode utilizar rede sob as permissões concedidas. Executar somente IPKs que você conhece.

Esta entrega NÃO possui: QEMU-user instalado no APK para executar hóspedes de outra arquitetura; compositor X11/Wayland; SDL integrado; áudio Linux; Vulkan/OpenGL de aplicativos Linux; integração webOS de serviços proprietários; DRM. A GPU e o som de aplicativos HTML5/WASM permanecem funcionando no Android WebView como antes. **RetroArch webOS nativo, jogos Linux com janela e aplicativos gráficos NÃO são declarados funcionais.**

## Diagnóstico: `not found` de ELF presente — 1.4.2

Na versão anterior, `NativeLinuxRuntime.launch` utilizava `runtimeRoot(hostAbi(context))` independentemente da classe ELF. Isso selecionava equivocadamente a sysroot Debian **ARM64** para executáveis Linux **ARM32** em aparelho ARM64.

Agora a seleção é independente: `NativeLinuxRunPlan.guestAbi(host, elf, loader32)` usa `armeabi-v7a` para `ELF32 ARM` e `arm64-v8a` para `ELF64 AArch64`. Um host ARM64 rodando guest ARM32 necessita de `PROOT_LOADER_32` e suporte real do kernel a AArch32. Se o kernel não tiver compatibilidade, será necessário acrescentar QEMU-user em uma versão futura.

O novo `ElfProgramInspector` lê passivamente a tabela de segmentos ELF (limites rígidos), localiza **PT_INTERP** e verifica se seu caminho absoluto existe **dentro do guest rootfs**. Se estiver ausente, o usuário recebe o caminho exato esperado. Um arquivo presente em `/app` pode gerar ENOENT em `execve()` quando o loader dinâmico indicado em `PT_INTERP` não existe, ou quando bibliotecas básicas são incompatíveis.

A execução sob BusyBox também verifica a existência do arquivo em `/app` antes de executar, emitindo `MAIZON_GUEST_PATH_MISSING` para distinguir falha de bind no PRoot.

**O suporte gráfico a Fruit Ninja / RetroArch não está implementado.** WebOS usa componentes proprietários e stacks gráficos próprios; o sucesso de compilação não significa execução do jogo no Android.

## Diagnóstico: 126 / Permission denied (corrigido no instalador 1.4.1)

Se aparecer `maizon: exec: line 0: /app/retroarch: Permission denied`, o código 126 significa que o shell **não conseguiu executar o arquivo**, não que o processador seja lento.

Foi encontrada uma falha de permissões específica: `IpkInstaller.extractTar` deixava o ELF sem o bit `x` original porque `FileOutputStream` não preserva o `0755` do TAR. A versão 1.4.1 restaura a permissão de execução na importação e repara ELFs já importados **apenas quando o usuário confirma Executar ELF**.

O Android 10+ também pode negar a execução direta por políticas SELinux/noexec. O bit `x` correto **não garante** que o sistema libere o `execve` para programas de terceiros. O PRoot pode ter problemas específicos com binários no armazenamento privado do aplicativo. Se o erro persistir, registrar resultado do probe, versão Android, lista de ABIs e saída completa do PRoot.

Para o caso `ELF32 ARM` em hospedeiro `arm64-v8a`, o aparelho também precisa oferecer ABI Android 32 bits funcional ou tradução QEMU-user. O APK atual inclui loader PRoot ARM32, mas **não inclui QEMU-user Android**. Confirmar abertura de janela, áudio e controles de RetroArch continua pendente.

## Fluxo no aplicativo

1. **+ Instalar IPK**; o instalador preserva a aplicação e o restante dos arquivos nativos externos em `.webos-package`, sem executá-los durante a instalação.
2. Na biblioteca, apps web continuam abrindo em WebView como antes.
3. Para IPKs ELF sem entrada HTML, toque em **▶ ELF**. O aplicativo apresenta a arquitetura, diagnóstico e o **aviso de segurança**.
4. O usuário pode escolher **Testar motor** para executar apenas o probe Linux oficial empacotado, ou **Executar ELF** para tentar o programa do IPK.
5. A primeira execução prepara a sysroot Debian da arquitetura do Android; os resultados incluem stdout/stderr, código de saída e erros. É possível interromper a execução. Não há tela de jogo nativo.
6. Em **Configurações → Testar execução Linux ELF (PRoot)**, execute o diagnóstico independente antes de testar IPKs de terceiros.

## Compatibilidade e desempenho

| APK instalado | Linux guest | Resultado esperado |
| --- | --- | --- |
| Android ARMv7 | ELF Linux armhf/ARMv7 | Execução possível via PRoot ARM32, ABI/dependências compatíveis |
| Android ARM64 | ELF Linux AArch64 | Execução possível via PRoot ARM64 |
| Android ARM64 com suporte real a 32 bits | ELF Linux armhf/ARMv7 | Tentativa experimental via loader ARM32 do PRoot |
| Android ARM64 exclusivamente 64 bits | ELF Linux ARM32 | **Incompatível** sem QEMU-user (não incluído) |
| Android ARM32 | ELF Linux ARM64 | **Incompatível** sem QEMU-user |
| Android ARM | ELF x86/x86_64 | **Incompatível** sem QEMU-user |
| Qualquer Android | Web HTML5/WASM | WebView, sem depender do PRoot |
| Qualquer Android | Linux GTK/Qt/SDL/RetroArch | Não há servidor gráfico/áudio integrado; interface ainda não funciona |

Compatibilidade de bibliotecas dinâmicas depende da arquitetura, versão de glibc requerida, símbolos disponíveis e demais dependências fornecidas pelo IPK. As sysroots baseadas em Debian Bookworm não recriam a ABI da TV LG. O desempenho não é comparável a um jogo Android nativo sem benchmark real; PRoot pode introduzir overhead importante em syscalls.

## Detalhes de engenharia

- `tools/build-native-runtime.sh`: reproduz builds do PRoot fork DroidNative fixado em SHA. Compila `libmaizon_proot.so`, `libmaizon_loader.so` e opcionalmente loader32 com NDK r29 e flags 16-KiB page-size.
- `tools/build-linux-sysroots.sh`: Linux Debian oficial com pacotes glibc + BusyBox. `tools/native/fetch-debian-libs.py` valida SHA-256 antes de extrair pacotes.
- `NativeLinuxRuntime`: extrai a sysroot com limites de tamanho/entrada/link, inicia o processo Java `ProcessBuilder` apontando para o executável no `nativeLibraryDir` com autenticação explícita do usuário, integra `PROOT_LOADER`, coleta stdout/stderr de forma limitada e interrompe por timeout.
- `NativeLinuxRunPlan`: separa cada argumento de comando, proíbe escaping do sandbox de arquivos e não concatena nomes do usuário em shell scripts. O programa é passado como `"$1"` ao busybox `sh`.
- `IpkInstaller`: mantém partes adicionais dos IPKs nativos com bibliotecas e outros recursos no mesmo sandbox.
- `MainActivity`: mantém WebView/Luna/WebGL/MaizoSNES e só abre PRoot após confirmação manual. O botão de teste usa ELF Linux compilado pelo CI e não executa arquivos de terceiros.
- APK com `android:extractNativeLibs=true`, pois Android 10+ bloqueia execução `execve()` de arquivos arbitrários no diretório gravável do app. PRoot e seus loaders são instalados como executáveis no diretório read-only das bibliotecas do APK. Arquivos hóspedes são mapeados pela camada PRoot.

O CI que compila ambos os motores e executa ambos os ELFs Linux **no runner Linux com QEMU** verifica integridade, sintaxe e integração do APK, **não execução no Android físico**. Em particular, restrições SELinux, ptrace, mmap, namespaces e diferentes kernels só podem ser avaliadas em dispositivo real.

## Licenças e fontes

- **PRoot**: projeto livre GPL-2.0, fork `DroidNative/proot` SHA `eb906a77c6632c1e69c367c9de289243cb909cab`. Código e licença disponíveis publicamente. O arquivo `PROOT_GPL2.txt` é incluído no APK.
- **Procedimento de build, talloc config**: adaptado com atribuição a `DroidNative/DroidNativeApp` (GPL-3.0-or-later).
- **talloc**: Samba talloc 2.5.0 (LGPL-3.0), arquivo de fonte verificado SHA256.
- **SQLite**: sqlite-autoconf 3540100 (domínio público).
- **Debian Bookworm armhf/arm64**: libc6, BusyBox-static, libgcc-s1, libstdc++6, zlib1g, com licenças incluídas em seus pacotes `/usr/share/doc`. Arquivos binários obtidos em `deb.debian.org` com hashes publicados no índice `Packages.xz`.
- O app não distribui firmware LG, DRM, ROMs, arquivos comerciais de jogos ou código proprietário.

Para assegurar redistribuição legal, confira as obrigações de fonte, notificações, bibliotecas ligadas estaticamente e disponibilização de objetos para relink das licenças copyleft. Este APK é experimental de testes; uma publicação comercial demanda auditoria específica dessas licenças.

## Plano de testes físicos (obrigatório para comprovar)

1. No Helio G99 ARM64, instale o APK e abra **Configurações → Testar execução Linux ELF**. Esperado: código de saída 0 e mensagem `MAIZON_LINUX_GUEST_OK`.
2. Repita em celular ARM32. O teste deve usar PRoot/ELF armhf, não arquivos ARM64.
3. Teste um ELF Linux estático confiável importado em IPK de laboratório: verify stdout, exit code, timeout e stop.
4. Teste um ELF dinâmico contra a glibc da sysroot. Se o ELF exigir bibliotecas ausentes, mostre erro (sem falso sucesso).
5. Carregue MaizoSNES WebAssembly e repita WebGL RGB565, áudio, WSS, UDP MZRC e TCP MZNP com dois aparelhos.
6. Não marque RetroArch nativo como "compatível" sem abrir GUI, renderizar quadros, reproduzir áudio e navegar com gamepad.

**Se a etapa 1 falhar, envie os logs da tela. O fato de o GitHub Actions estar verde não significa execução Android validada.**
