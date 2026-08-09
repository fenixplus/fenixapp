# Plano de Correção: Diferenciação de Falhas e Skip Imediato

O objetivo é garantir que o player tente o software após a falha do hardware, mas pule **imediatamente** para a próxima fonte se o software também falhar, eliminando o atraso de 1 minuto.

## Mudanças Propostas

### PlayerScreen.kt

#### [MODIFY] [PlayerScreen.kt](file:///C:/Users/Elias/StudioProjects/ARVIO/app/src/main/kotlin/com/arflix/tv/ui/screens/player/PlayerScreen.kt)

1.  **Refinar Bloqueio de Skip em `onPlayerError`**:
    Alterar a condição de bloqueio para verificar se o player que falhou já era uma tentativa de software.
    ```kotlin
    // Bloqueia o skip apenas se estivermos TRANSICIONANDO para software.
    // Se o player atual (preferExtensionDecoder) já for software, permite o skip.
    if (localPreferSoftwareDecoder && !preferExtensionDecoder && !hasPlaybackStarted && !firstVideoFrameRendered) {
        return
    }
    ```

2.  **Reset explícito em `tryAdvanceToNextStream`**:
    Garantir que ao pular de fonte, todos os estados de "tentativa de software" e "posição de fallback" sejam limpos imediatamente.
    ```kotlin
    localPreferSoftwareDecoder = false
    autoFallbackPosition = -1L
    ```

3.  **Logs de Diagnóstico**:
    Adicionar log quando o software falha e o skip é disparado, para confirmar a agilidade do processo.

## Plano de Verificação

### Teste Manual
1. Abrir vídeo que falha em hardware.
2. Confirmar transição para software (Log: `auto-software-fallback`).
3. Se software falhar (Log: `🎬 Video Decoder Initialized: ... (SOFTWARE)` seguido de `❌ Player Error`), o player deve exibir imediatamente a mensagem de "Troca de fonte" e carregar o próximo vídeo.
4. O próximo vídeo deve iniciar em modo hardware.

### Logs Esperados
- `PlayerDiagnostics I 🎬 auto-software-fallback... triggering recreation`
- `PlayerDiagnostics I 🛠️ Creating ExoPlayer instance (preferExtensionDecoder=true...)`
- `PlayerDiagnostics I 🎬 Video Decoder Initialized: ... (SOFTWARE)`
- `PlayerDiagnostics E ❌ Player Error: ERROR_CODE_DECODING_FAILED`
- `[PlaybackStartup] advancing source from index=...` (Imediato após o erro acima).
