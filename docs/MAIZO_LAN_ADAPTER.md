# MaizoSNES webOS bridge → Android Java LAN adapter (simulador 1.2.0)

## Objetivo e escopo

O IPK MaizoSNES foi desenvolvido para LG webOS 22. Ele contém um serviço **Node.js da televisão** em `services/com.maizon.maizosnes.webos.bridge/`, chamado por `webOS.service.request("luna://com.maizon.maizosnes.webos.bridge",{method:"open",parameters:{subscribe:true}})`. O Android WebView consegue executar os scripts do *aplicativo web* mas **não** executa serviços Node.js da LG.

A versão 1.2.0 implementa um **adaptador Java Android** que responde à mesma chamada e reproduz os protocolos de comunicação LAN do serviço JS original. O IPK original não é modificado e o APK MaizoSNES Android também permanece intocado.

**Não é uma VM Node.js**, não fornece `require('webos-service')`, não executa JS services arbitrários nem ferramentas externas. Somente `com.maizon.maizosnes.webos.bridge/open` e `/health` são reconhecidos, e apenas para o IPK `com.maizon.maizosnes.webos`. Chamada aos demais serviços mantém erro `UNSUPPORTED`.

## Topologia

    MaizoSNES Android APK (telefone 2)
       ├── MZRC v1 UDP → 55437 (gamepad P2/P3/P4)
       ├── MZSCAN2/3 UDP → 55436 (descoberta)
       └── MZNP TCP → 55435 (LAN multiplayer, negocia lockstep/rollback existente)
                                     │
                           Android Java sockets
                                     │
                        WS localhost 127.0.0.1:porta
                               /bridge + token
                                     │
                     MaizoSNES webOS native-bridge.js
                                     │
                       ROM / WASM / UI / controles

O navegador webOS IPK recebe a porta e um token aleatório de 192 bits por Luna `open`, com `subscribed:true`. O endpoint WS exige o token `auth` como primeiro comando, e só aceita origem HTTP exata do IPK ativo. Esse WS é local ao Android, não exposto na rede Wi-Fi. O serviço não permite abrir portas arbitrárias nem encaminhar bytes para hosts públicos. Quadros binários são encaminhados apenas à sessão TCP selecionada.

Portas externas são ativadas **sob demanda** quando o IPK envia os comandos de jogo. Não há serviços em segundo plano depois de fechar o IPK. O Android precisa permanecer ativo na tela para hospedagem estável.

## MZRC/1 por UDP 55437

Quadros com magic `MZRC`, versão 1 e tipos:

| Tipo | Nome | Fluxo |
|---|---|---|
| 1 | HELLO | Android → simulador: nonce (8 bytes), nome MUTF |
| 2 | CHALLENGE | Simulador → Android: nonce |
| 3 | ACCEPT | Simulador → Android: nonce, token (8), slot P2/P3/P4, título MUTF |
| 4 | REJECT | Simulador → Android: nonce, mensagem MUTF |
| 5 | INPUT | Android → simulador: token, seq int32 BE, botões uint16 BE |
| 6 | PING | Android → simulador: token, timestamp 8 bytes |
| 7 | PONG | Simulador → Android: mesmo token e timestamp |
| 8 | BYE | Android → simulador: token |

O anfitrião do IPK recebe evento `pad-offer`, mostra confirmação de autorização e responde via `pad-decide`. Não há autoaprovação silenciosa. Controles vencem em 12 s de inatividade, entradas pressionadas são liberadas em 650 ms, ofertas pendentes vencem em 15 s. Entradas fora de ordem, pacotes malformados, endpoints não autorizados ou acima de 256 bytes são ignorados.

## LAN multiplayer MZNP/TCP 55435

Os comandos do IPK `tcp-host`, `tcp-join` e `tcp-stop` controlam uma **única** sessão TCP. A ligação binária usa a interface `LocalSocket` do IPK, mantendo intacto o protocolo MZNP v6/v7 e autorização/transferência de ROM já implementadas na aplicação. O adaptador **não lê, altera ou compartilha ROMs** — apenas encaminha bytes para a sessão autorizada. Não adiciona suporte GekkoNet UDP cruzado que ainda não exista no protocolo Android/webOS.

O WS localhost aceita chunks binários de até 32 KiB. O envio ao navegador é fragmentado em quadros WS binários de até 32 KiB, conforme o serviço original.

## Descoberta UDP 55436

O servidor responde a `MZSCAN2`/`MZSCAN3` recebidos de IP local com os anúncios `MZOFFER2`/`MZOFFER3`, nome de jogo truncado e prefixo de hash declarado pelo IPK. A busca por salas usa UDP broadcast e interfaces privadas locais, com 1,8 s de janela. A descoberta pode não funcionar se AP/Wi-Fi filtrar broadcast ou se a rede tiver isolamento entre clientes.

O simulador declara uso de `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE` e `INTERNET`, habilitando MulticastLock apenas enquanto a descoberta está ativa.

## Compatibilidade do IPK sem modificação

O `native-bridge.js` original contém um gate `/Android/i.test(navigator.userAgent)` que desativa a ponte mesmo quando o ambiente simulado fornece sockets. Para **este ID de IPK e somente com o adaptador ativado**, o WebView recebe o UA de perfil LG legado. Isso libera a verificação original de recursos, permitindo que o IPK tente o handshake Luna real do adaptador. Outros IPKs mantêm o perfil e o UA configurados.

Desabilitar **Configurações → LAN nativa MaizoSNES** remove o ajuste de UA e impede `open`, mantendo WSS como fallback.

## Aceitação no dispositivo físico

- [x] Adaptador protocolar Java acrescentado.
- [x] Codificação/testes de pacotes MZRC HELLO, CHALLENGE, ACCEPT, INPUT, PING/PONG, BYE e validação de IP privado.
- [ ] Instalar APK 1.2.0 sobre 1.1.0, abrir o IPK MaizoSNES e importar uma ROM autorizada.
- [ ] Ao iniciar a ROM local, observar `pad-running: 55437` e `tcp-listening: 55435`.
- [ ] Em outro celular com APK MaizoSNES Android, habilitar gamepad LAN e conectar ao IP do celular que hospeda o IPK.
- [ ] Confirmar oferta P2/P3/P4 na tela IPK, autorizar, receber ACK e controlar o jogo sem botões presos.
- [ ] Confirmar UDP descoberta das salas e multiplayer TCP MZNP com ROM autorizada, se o IPK suportar a configuração.
- [ ] Alternar apps/sair da ROM, confirmar fechamento das portas e que WSS/online continua funcional.
- [ ] Repetir com Wi-Fi isolado e sem permissões multicast: falhar claramente sem quebrar o emulador.
- [ ] Confirmar no dispositivo que o WS só aceita origin correto e segredo correto.

**CI verde não demonstra automaticamente funcionamento real de UDP entre aparelhos.** A compatibilidade LAN depende também de permissões, roteador, firewall e suporte à comunicação local.

## Segurança

- Loopback WS requer Origin exata e token aleatório por sessão.
- O código somente hospeda o serviço para `com.maizon.maizosnes.webos`, nunca para ID arbitrário de IPK.
- UDP/TCP aceitam redes IPv4 RFC1918 ou loopback.
- Gamepads precisam de consentimento na UI; limite de 3.
- Nenhuma ROM, identidade pessoal ou log é enviada para nosso servidor por este adaptador.
- Conexões de rede locais não são criptografadas; use somente rede confiável. WSS online existente continua criptografado.
- Se uma porta LAN já estiver em uso, retorna falha explícita e mantém recursos que já funcionam.

## Referência técnica da implementação interoperável

O formato de pacotes, eventos WS e portas seguem o projeto do usuário `maizon/MaizoSNES-WEBOS`: `services/com.maizon.maizosnes.webos.bridge/lan.js`, `service.js`, `webos/native-bridge.js`. Não são incluídos binários proprietários LG/Node.
