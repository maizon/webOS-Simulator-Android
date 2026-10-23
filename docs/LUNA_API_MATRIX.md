# Catálogo de compatibilidade Luna — webOS TV 22 (Android Runtime 1.1.0)

**Este NÃO é um emulador de firmware LG.** É uma implementação parcial e independente das interfaces públicas usadas por aplicativos webOS de TV. A LG disponibiliza APIs exclusivas de hardware, DRM, contas e binários que **não funcionam por simplesmente copiar nomes de funções ou respostas JSON**. Essas chamadas continuam a retornar erro transparente `returnValue:false`, `errorText` e `__simulator:"UNSUPPORTED"`.

Perfil visual de referência: **UQ8050PSB / webOS TV 22**, Chromium 87 da TV apenas no User-Agent do modo LG legado; o mecanismo real do simulador é o Android System WebView.

## APIs implementadas ou aproximadas

| Interface | Método | Resultado no simulador | Precisão |
|---|---|---|---|
| webOSTV.js | webOS.platform.tv, libVersion | Simulado (identificação) | MOCK |
| webOSTV.js | webOS.deviceInfo(callback), webOS.systemInfo | Perfil da TV e idioma/fuso Android | TV_PROFILE/ANDROID |
| webOSTV.js | webOS.fetchAppId(), fetchAppInfo(cb), fetchAppRootPath(), appInfo | Lê metadados do IPK corrente | SIMULATOR |
| webOSTV.js | webOS.service.request / Request | URI, method, parameters, params, onSuccess, onFailure, onComplete, subscribe, cancel | PARCIAL |
| webOSTV.js | webOS.keyboard.isShowing(), platformBack() | DOM/simulador | APROXIMADO |
| webOSDev | launchParams(), launch() | Parâmetros/relançamento do mesmo IPK | PARCIAL |
| webOSDev | connection.getStatus(), LGUDID(), drmAgent() | Rede Android; identificador único e DRM negados | REAL_ANDROID / UNSUPPORTED |
| Legacy | PalmServiceBridge, PalmSystem, webOSSystem | Interface JS e callbacks JSON; identificação aproximada | PARCIAL |
| com.palm.connectionmanager | getStatus | Rede Android validada, Wi-Fi/Ethernet/Celular, sem falsificar SSID | ANDROID_NETWORK |
| com.webos.service.connectionmanager | getStatus | Mesmo serviço, URI alternativa histórica | ANDROID_NETWORK |
| com.palm.systemservice | time/getSystemTime | Hora/fuso de verdade do Android | ANDROID_CLOCK |
| com.webos.settingsservice | getSystemSettings | localeInfo, option/country, smartServiceCountryCode2, audioGuidance | ANDROID_LOCALE |
| com.webos.service.tv.systemproperty | getSystemInfo | modelName, sdkVersion, UHD, _3d: apenas modelo simulado | TV_PROFILE |
| com.webos.audio | setMuted, volumeUp, volumeDown | Volume virtual por IPK; **não** altera som real Android | VIRTUAL_AUDIO |
| com.webos.applicationManager | getAppLoadStatus | Identifica apenas IPKs já instalados no simulador | SIMULATOR_LIBRARY |
| com.webos.applicationManager | launch | Apenas relançamento do próprio IPK e evento webOSRelaunch; outros negados | SIMULATOR_SELF_RELAUNCH |
| com.palm.db | put, get, find, del, putKind | Base JSON privada por IPK; find com =; até 256 objetos e 1 MiB | APP_PRIVATE_DB |
| com.webos.service.tv.display | getDisplayStatus | Resolução lógica 1920×1080 | TV_PROFILE |
| com.webos.service.config | getConfigs | Objeto vazio sinalizado como mock | MOCK |

**Notação:** "REAL_ANDROID" significa dados do Android, NÃO dados da TV. "TV_PROFILE", "MOCK" e "VIRTUAL_*" são simulações. "UNSUPPORTED" indica resposta de erro, não uma funcionalidade operacional.

### Inspeção no aplicativo

1. Abra um IPK no simulador.
2. Clique em **⋮ → Teste de APIs Luna webOS 22**.
3. O teste consulta somente serviços de leitura (rede, horário, localidade, modelo e status do app).
4. O teste inclui uma consulta DRM que **deve falhar explicitamente** — evita falsa compatibilidade.
5. Clique em **Logs** para observar as marcas `LUNA_*` e os códigos de erro.

### Assinatura de callback e subscriptions

As chamadas `webOS.service.request(uri,{method,parameters,onSuccess,onFailure,onComplete,subscribe})` enviam a primeira resposta de forma assíncrona aos callbacks JS. `cancel()` interrompe entrega posterior. `PalmServiceBridge.call(uri,json)` envia string JSON ao `onservicecallback`.

Assinaturas de rede/hora/configuração utilizam uma *consulta periódica* limitada a 30 segundos, com notificação apenas ao detectar mudança, **não** o barramento LS2 real da LG. Número de assinaturas limitado para preservar desempenho. Uma assinatura com erro não é mantida. Com páginas encerradas, as assinaturas são canceladas.

### Banco de dados e segurança

O banco `com.palm.db` parcial vive em SharedPreferences por ID de IPK; suporta subconjunto de consultas sem transações, índices, watchers, ACL ou migração completa de kinds. Registros não são compartilhados entre IPKs. `get`/`put`/`find`/`del` retornam objetos JSON próprios da camada de compatibilidade. Para bancos grandes, prefira IndexedDB do navegador, que já existia na versão 1.0.

Nenhuma operação finge ter alterado hardware ou configurações da Smart TV. O volume do serviço `com.webos.audio` é **virtual**, por exemplo: o resultado pode indicar volume 51, mas isso não aumenta o áudio reproduzido pelo Android. É uma compatibilidade de controle de estado para apps de teste, não saída de áudio TV.

As chamadas relacionadas a DRM, Keymanager, device ID único LGUDID, câmera, BLE, media DB, áudio/canais da TV, Magic Remote com sensores físicos e serviços proprietários **não estão implementadas**. Chamar `webOSDev.LGUDID` ativa o callback de falha, sem inventar ou coletar ID de hardware do celular.

**Permissões ACG:** na Smart TV real, cada API depende das permissões `requiredACG` do appinfo.json e do sistema da LG. Este simulador não reproduz os controles ACG/LS2 reais; não o use para auditar segurança ou aprovação de apps oficiais.

### Fontes públicas oficiais da LG (documentação, não bibliotecas proprietárias copiadas)

- Luna API overview: https://webostv.developer.lge.com/develop/references/luna-service-introduction
- webOSTV.js webOS API: https://webostv.developer.lge.com/develop/references/webostvjs-webos
- webOSTV.js webOSDev API: https://webostv.developer.lge.com/develop/references/webostvjs-webosdev
- Connection Manager: https://webostv.developer.lge.com/develop/references/connection-manager
- Settings Service: https://webostv.developer.lge.com/develop/references/settings-service
- System Service: https://webostv.developer.lge.com/develop/references/system-service
- TV Device Info: https://webostv.developer.lge.com/develop/references/tv-device-information
- Audio: https://webostv.developer.lge.com/develop/references/audio
- Application Manager: https://webostv.developer.lge.com/develop/references/application-manager
- ACG: https://webostv.developer.lge.com/develop/guides/acg-guide

## Limites e testes necessários

O projeto preserva o funcionamento existente com emulação Snes9x WebAssembly, WebGL e jogos importados, mas código e CI automatizado **não substituem testes reais** de aplicações IPK. Não declare compatibilidade de 99,9% ou paridade total com a LG UQ8050PSB. Para cada serviço adicional futuro, confirme formatos, callbacks, tipo de retorno, eventos, ACG, permissões e casos de erro e adicione testes.
