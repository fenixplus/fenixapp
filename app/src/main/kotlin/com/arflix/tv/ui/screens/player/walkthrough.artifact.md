# Walkthrough: Otimização do Fallback e Skip Imediato

Refinei a lógica de fallback para garantir que o player seja ágil: ele tenta o software assim que o hardware falha, mas não perde tempo se o software também não funcionar.

## Mudanças Realizadas

### [PlayerScreen.kt](file:///C:/Users/Elias/StudioProjects/ARVIO/app/src/main/kotlin/com/arflix/tv/ui/screens/player/PlayerScreen.kt)

1.  **Skip Inteligente**: Ajustei a condição de bloqueio no tratamento de erro. Agora, o player consegue distinguir se o erro veio da tentativa de Hardware ou da tentativa de Software.
    - Se o **Hardware** falha: O skip é bloqueado para dar chance ao Software iniciar.
    - Se o **Software** falha: O skip é liberado instantaneamente para a próxima URL.
2.  **Limpeza de Estados**: Adicionei o reset explícito de `localPreferSoftwareDecoder` e `autoFallbackPosition` na função `tryAdvanceToNextStream`. Isso garante que, ao pular para um novo vídeo, o player comece do "zero" (tentando hardware primeiro) e não tente retomar a posição de erro do vídeo anterior.
3.  **Logs de Auditoria**: Adicionei um log informativo (`🎬 Blocking skip because software transition is in progress...`) que aparecerá apenas durante os milissegundos de troca entre hardware e software.

## Verificação

- **Compilação**: APK `sideloadDebug` gerado com sucesso.
- **VCS**: Alterações commitadas com mensagens claras.

> [!IMPORTANT]
> O player agora deve ser muito mais rápido para desistir de um arquivo que não toca em nenhum dos dois modos, pulando para a próxima fonte quase imediatamente após a falha do decodificador do Google.

render_diffs(file:///C:/Users/Elias/StudioProjects/ARVIO/app/src/main/kotlin/com/arflix/tv/ui/screens/player/PlayerScreen.kt)
