# Tarefas: Fallback Automático para Decodificador de Software

- `[x]` Atualizar chaves do `LaunchedEffect` de carga no `PlayerScreen.kt`
- `[x]` Ajustar lógica de `onPlayerError` para resetar o watchdog ao trocar para software
- `[x]` Refinar o bloqueio de salto de fonte em `PlayerScreen.kt`
- `[x]` Realizar commit das alterações
- `[x]` Compilar versão sideload debug
- `[x]` Implementar `autoFallbackPosition` para retomada precisa
- `[x]` Corrigir logs de diagnóstico para Log.i
- `[x]` Garantir interrupção imediata do skip ao falhar hardware
- `[x]` Diferenciar falha de software para permitir skip imediato
- `[x]` Resetar estados de fallback em `tryAdvanceToNextStream`
