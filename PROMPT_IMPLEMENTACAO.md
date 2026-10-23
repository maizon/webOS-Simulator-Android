# Missão de engenharia — webOS IPK Simulator Android

Repositório: https://github.com/maizon/webOS-Simulator-Android

Objetivo: construir um APK instalável que receba aplicativos LG webOS TV 22 (.ipk) e tente executá-los sem modificar os IPKs. Perfil principal: LG UQ8050PSB. Prioridade de validação: MaizoSNES webOS. Nunca alterar a versão Android original do MaizoSNES.

Requisitos de execução:
1. Auditar estado atual da branch main e preservar código existente. Implementar todas as mudanças de um ciclo em **um único commit de implementação**, sem commits parciais. Em repositório vazio, inicialização mínima pode ser necessária.
2. Criar app Android Java/Kotlin com instalador de IPK seguro: distinguir arquivo `ar`/tar, extrair `data.tar.gz`, validar `appinfo.json`, evitar path traversal, symlink, decompression bomb, limitar tamanho, rejeitar binários nativos como executáveis.
3. Criar biblioteca de apps instalados (executar, desinstalar, logs), runtime WebView local com correto MIME para WASM, injeção de shim e suporte a Storage Access Framework para arquivos.
4. Implementar perfil JSON UQ8050PSB; simular `webOS`, `webOSSystem`, `webOS.service.request`, `PalmServiceBridge` e Luna necessários, logando REAL/MOCK/UNSUPPORTED, sem inventar interoperabilidade.
5. Implementar navegação via controle remoto virtual e entradas Android; suporte a gamepads Bluetooth/USB quando WebView comportar. Inspecionar WebAudio, WebGL, WebAssembly, ROM picker e vídeo.
6. Configurar servidor HTTP `127.0.0.1` com headers COOP/COEP opcionais e contexto seguro, verificando `crossOriginIsolated` e `SharedArrayBuffer` de verdade. Evitar `file://` e isolar origens por app.
7. Permitir rede WebSocket/WebRTC/HTTP conforme permissões Android e comportamento do IPK; não prometer multiplayer sem testes em dois dispositivos.
8. Criar CI GitHub Actions com JDK 17, SDK Android, testes unitários e geração de APK debug instalável + release unsigned, publicar artefatos por commit. Avaliar erros de CI antes de anunciar sucesso.
9. Documentar o que é funcional, experimental e não suportado. Não incluir firmware LG, DRM, ROMs protegidas nem bibliotecas proprietárias.
10. Testar com um IPK homebrew sintético, depois solicitar/importar IPK real do MaizoSNES para validar `appinfo.json`, serviços Luna, arquivos .wasm e .so, CORS, COOP/COEP, carregamento de ROM e multiplayer.

Critério de aceite: workflow verde com APK debug e um IPK de teste abrindo HTML. **MaizoSNES e compatibilidade total só podem ser confirmados por testes reais.** Priorizar fidelidade técnica em vez de promessas percentuais.
