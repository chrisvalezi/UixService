# UixService – Serviço de Automação via Accessibility no Android

## Visão geral

**UixService** é um aplicativo Android que expõe a árvore de acessibilidade da tela atual e permite enviar comandos de automação **direto via shell**, sem precisar de Python, ADB externo escutando porta, nem app com UI.

Ele roda como um `AccessibilityService`, acompanha a tela ativa e abre um servidor TCP **local** em:

- `127.0.0.1:9001` (apenas dentro do próprio Android / container)

A partir daí, qualquer processo com acesso ao shell (ex.: Redroid, Termux, container com `adb shell`) pode:

- inspecionar a UI atual em formato **JSON**
- localizar elementos por texto ou `viewId`
- clicar em elementos
- digitar texto em campos (`EditText`)
- executar ações globais (HOME, BACK, etc.)
- fazer swipe/scroll
- aguardar elementos aparecerem (wait com timeout)

Tudo isso apenas mandando **uma linha de texto** por conexão TCP.

---

## Arquitetura

Componentes principais:

- `UixAccessibilityService`
  - Serviço de acessibilidade responsável por:
    - receber eventos de UI
    - manter o último `rootInActiveWindow` em memória
    - converter a árvore de `AccessibilityNodeInfo` para JSON
    - executar gestos (clique e swipe)

- `CommandServer`
  - Servidor TCP embutido no app:
    - escuta em `127.0.0.1:9001`
    - lê uma linha de comando por conexão (`DUMP`, `CLICK_ID`, etc.)
    - executa a ação correspondente usando o `UixAccessibilityService`
    - responde sempre em **JSON**, em uma única linha

Fluxo simplificado:

1. O serviço é ativado nas Configurações de Acessibilidade.
2. A cada mudança de tela, `onAccessibilityEvent` atualiza o “root” em memória.
3. Um cliente no shell envia um comando via `nc` (netcat) para `127.0.0.1:9001`.
4. O `CommandServer` interpreta o comando, consulta a árvore de acessibilidade e devolve JSON.

---

## Protocolo / Usage rápido

### Conexão

- Protocolo: TCP
- Host: `127.0.0.1`
- Porta: `9001`
- Formato: **1 comando por conexão**, 1 linha de texto, resposta em 1 linha JSON

Exemplos usando `nc` (dentro do Android):

```sh
# Dump completo da tela atual
printf 'DUMP\n' | nc 127.0.0.1 9001

# Clicar em um botão pelo texto visível
printf 'CLICK_TEXT Entrar\n' | nc 127.0.0.1 9001

# Digitar em um campo pelo viewId
printf 'SET_TEXT_ID com.spotify.music:id/username_text meuemail@teste.com\n' | nc 127.0.0.1 9001
