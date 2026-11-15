# Legendas (protótipo)

App separado do Cofre (pacote `com.vault.legendas`, instala ao lado, não toca nos seus dados) para testar
**legenda automática offline**:

1. Extrai o áudio do vídeo (MediaCodec) e converte para 16 kHz mono.
2. Transcreve com o **Whisper** (whisper.cpp v1.7.4, compilado no app) e já recebe o horário de cada fala.
3. Traduz para português com o **ML Kit Translate** (offline depois de baixar o pacote do idioma).
4. Toca o vídeo com a legenda sincronizada (original, português ou os dois) e exporta `.srt`.

Nada do vídeo sai do celular. A internet é usada só para baixar o modelo (uma vez) e o pacote de tradução (uma vez por idioma).

## Como usar
1. Escolha e **baixe um modelo** (Small é o recomendado; comece pelo Base se quiser testar rápido).
2. Escolha o **idioma da fala** (ou "Detectar").
3. Escolha o **vídeo** e toque em **Gerar legenda**. Mantenha o app aberto.
4. Quando terminar, **Assistir com legenda**. Dá para ajustar a sincronia em ±0,5 s e tocar numa fala para pular até ela.

## O que anotar nos testes
- Velocidade ("x o tempo real") por modelo e o aquecimento/bateria do aparelho.
- Acertos e erros em espanhol/russo; se o "Detectar" acerta o idioma.
- Se a sincronia fica boa ou se precisa de ajuste manual.
- Falas inventadas em trechos de música ou silêncio.

## Build
Push na `main` roda o workflow `Build APK` (gera keystore de teste, compila o código nativo com NDK e publica o APK
na release `latest`). O primeiro build baixa o whisper.cpp e leva uns 8–12 minutos.

## Se algo falhar
- **Erro de compilação nativa**: o log do passo "Build release APK" mostra a linha; é o ponto mais provável de ajuste.
- **App fecha ao gerar ("Illegal instruction")**: o CMake usa `-march=armv8.2-a+fp16+dotprod` (rápido, mas exige chip de 2019+).
  Se acontecer, remova esse trecho de `app/src/main/cpp/CMakeLists.txt`.
