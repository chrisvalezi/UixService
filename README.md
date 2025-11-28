# UixService – Serviço de Automação via Accessibility no Android

## Visão geral

**UixService** é um aplicativo Android que expõe a árvore de acessibilidade da tela atual e permite enviar comandos de automação **direto via shell**, sem precisar de Python, servidor externo nem app com interface gráfica.

Ele roda como um `AccessibilityService`, acompanha a tela ativa e abre um servidor TCP **local** em:

- `127.0.0.1:9001` (apenas dentro do próprio Android / container)

A partir daí, qualquer processo com acesso ao shell (ex.: container Android, Termux, `adb shell`, etc.) pode:

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
  Serviço de acessibilidade responsável por:
  - receber eventos de UI
  - manter o último `rootInActiveWindow` em memória
  - converter a árvore de `AccessibilityNodeInfo` para JSON
  - executar gestos (clique e swipe)

- `CommandServer`  
  Servidor TCP embutido no app, responsável por:
  - escutar em `127.0.0.1:9001`
  - ler uma linha de comando por conexão (`DUMP`, `CLICK_ID`, etc.)
  - executar a ação correspondente usando o `UixAccessibilityService`
  - responder sempre em **JSON**, em uma única linha

Fluxo simplificado:

1. O serviço é ativado nas Configurações de Acessibilidade.
2. A cada mudança de tela, `onAccessibilityEvent` atualiza o “root” em memória.
3. Um cliente no shell envia um comando via `nc` (netcat) para `127.0.0.1:9001`.
4. O `CommandServer` interpreta o comando, consulta a árvore de acessibilidade e devolve JSON.

---

## Protocolo / Usage

### Conexão

- Protocolo: TCP  
- Host: `127.0.0.1`  
- Porta: `9001`  
- Formato: **1 comando por conexão**, 1 linha de texto (terminada com `\n`), resposta em 1 linha JSON.

Exemplos usando `nc` (dentro do Android):

```sh
# Dump completo da tela atual
printf 'DUMP\n' | nc 127.0.0.1 9001

# Clicar em um botão pelo texto visível
printf 'CLICK_TEXT Entrar\n' | nc 127.0.0.1 9001

# Digitar em um campo pelo viewId
printf 'SET_TEXT_ID com.example.app:id/username_input usuario@example.com\n' | nc 127.0.0.1 9001
```
---

## Comandos suportados
### 1. DUMP
Descrição:Retorna a árvore completa de acessibilidade da tela atual.

Resposta (exemplo simplificado):
```json
{
  "text": "",
  "content_desc": "",
  "view_id": "",
  "class_name": "android.widget.FrameLayout",
  "package_name": "com.example.app",
  "clickable": false,
  "enabled": true,
  "focusable": false,
  "checked": false,
  "editable": false,
  "bounds": { "left": 0, "top": 0, "right": 413, "bottom": 693 },
  "children": [ ... ]
}
```
Cada nó contém pelo menos:

text
content_desc
view_id
class_name
package_name
clickable, enabled, focusable, checked, editable
bounds (left, top, right, bottom)
children (lista de nós filhos)

### 2. FIND_TEXT
Comando: 
```text
FIND_TEXT <texto>
```
Descrição: Procura o primeiro node cujo text ou content_desc contenha <texto> (case-insensitive).

Resposta (encontrou):
```json
{
  "found": true,
  "node": {
    "text": "Entrar",
    "content_desc": "",
    "view_id": "com.example.app:id/login_button",
    "class_name": "android.widget.Button",
    "package_name": "com.example.app",
    "clickable": true,
    "enabled": true,
    "focusable": true,
    "checked": false,
    "editable": false,
    "bounds": { "left": 151, "top": 348, "right": 262, "bottom": 389 },
    "children": []
  }
}
```
Resposta (não encontrou):
```json
{"found": false}
```
### 3. CLICK_TEXT
Comando: 
```text
CLICK_TEXT <texto>
```
Descrição: Procura um node por text/content_desc e envia um clique (gesto) no centro do seu bounding box.

Resposta (sucesso):

```json
{"ok": true, "x": 206, "y": 368}

```
Resposta (falha):
```json
{"ok": false, "error": "node_not_found"}
```

### 4. FIND_ID
Comando: 
```text
FIND_ID <view_id>
```
Descrição: Procura um node pelo viewIdResourceName exato (o campo view_id que aparece no DUMP).

Resposta (encontrou):
```json
{
  "found": true,
  "node": {
    "text": "",
    "content_desc": "",
    "view_id": "com.example.app:id/username_input",
    "class_name": "android.widget.EditText",
    "package_name": "com.example.app",
    "clickable": true,
    "enabled": true,
    "focusable": true,
    "checked": false,
    "editable": true,
    "bounds": { "left": 16, "top": 127, "right": 397, "bottom": 175 },
    "children": []
  }
}
```
Resposta (não encontrou):
```json
{"found": false}
```

### 5. CLICK_ID
Comando: 
```text
CLICK_ID <view_id>
```
Descrição: Procura um node pelo view_id e envia um clique no centro.

Resposta (sucesso):
```json
{"ok": true, "x": 206, "y": 368}
```
Resposta (falha):
```json
{"ok": false, "error": "node_not_found"}
```

### 6. SET_TEXT_ID
Comando: 
```text
SET_TEXT_ID <view_id> <texto>
```
Descrição: Define o texto de um campo (EditText) identificado por view_id, usando a ação ACTION_SET_TEXT.

Tudo após o primeiro espaço depois do view_id é considerado parte do <texto>.

Exemplo:
```sh
printf 'SET_TEXT_ID com.example.app:id/username_input usuario@example.com\n' | nc 127.0.0.1 9001
```
Resposta (sucesso):
```json
{"ok": true}
```
Resposta (falhas):
```json
{"ok": false, "error": "node_not_found"}
```
ou
```json
{"ok": false, "error": "action_failed"}
```

### 7. GLOBAL
Comando: 
```text
GLOBAL <ação>
```
Ações suportadas:

BACK

HOME

RECENTS

NOTIFICATIONS

QUICK_SETTINGS

Exemplos:
```sh
printf 'GLOBAL BACK\n' | nc 127.0.0.1 9001
printf 'GLOBAL HOME\n' | nc 127.0.0.1 9001
```
Resposta (sucesso):
```json
{"ok": true, "action": "BACK"}
```
Resposta (erro):
```json
{"ok": false, "error": "unknown_global_action"}
```

### 8. SWIPE
Comando: 
```text
SWIPE x1 y1 x2 y2 [durationMs]
```
x1, y1: coordenadas iniciais

x2, y2: coordenadas finais

durationMs: duração opcional do gesto em milissegundos (default ~300ms)

Exemplo:
```sh
printf 'SWIPE 200 600 200 200 300\n' | nc 127.0.0.1 9001
```
Resposta:
```json
{
  "ok": true,
  "x1": 200,
  "y1": 600,
  "x2": 200,
  "y2": 200,
  "duration": 300
}
```
Se os argumentos forem inválidos:

```json
{"ok": false, "error": "invalid_arguments"}
```

### 9. WAIT_TEXT

Comando: 
```text
WAIT_TEXT <texto> <timeoutMs>
```
Descrição: Fica consultando a árvore de acessibilidade até encontrar <texto> ou até estourar <timeoutMs>.

Exemplo:
```sh
printf 'WAIT_TEXT Entrar 10000\n' | nc 127.0.0.1 9001
```
Resposta (encontrou):
```json
{
  "ok": true,
  "node": { }
}
```
Resposta (timeout):
```json
{"ok": false, "error": "timeout"}
```

### 10. WAIT_ID
Comando:
```text
WAIT_ID <view_id> <timeoutMs>
```
Descrição: Idêntico ao WAIT_TEXT, mas usando o view_id exato.

Exemplo:
```sh
printf 'WAIT_ID com.example.app:id/login_button 15000\n' | nc 127.0.0.1 9001
```
Resposta (encontrou):
```json
{
  "ok": true,
  "node": { }
}
```
Resposta (timeout):
```json
{"ok": false, "error": "timeout"}
```

### 11. Respostas de erro genéricas
Quando um comando não é reconhecido:
```json
{"ok": false, "error": "unknown_command"}
```
Quando os argumentos são inválidos (ex.: SWIPE com poucos parâmetros):
```json
{"ok": false, "error": "invalid_arguments"}
```

### Voce pode achar o .apk no Realeses

### Ou pode fazer seu propio build
Requisitos para build

Para compilar o APK deste projeto, é necessário:

JDK 17 (ou compatível)

Gradle wrapper já incluído no projeto (./gradlew)

versão alvo usada: Gradle 8.7

Android SDK instalado e configurado

sdk.dir definido em local.properties na raiz do projeto, por exemplo:
```properties
sdk.dir=/home/seuusuario/android-sdk
```
Componentes do SDK (instalados via sdkmanager ou Android Studio):

platforms;android-34

build-tools;34.0.0 (ou versão equivalente suportada pelo projeto)

platform-tools

Configurações principais do módulo app (no build.gradle do módulo):

compileSdk 34

minSdk 24

targetSdk 34

Dependências principais:
```gradle
implementation "androidx.core:core-ktx:1.12.0"
```
Plugins usados:
```gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}
```
Obs.: ajuste versões de SDK / dependências conforme sua stack, se necessário.

### Como compilar
Na raiz do projeto:
```bash
./gradlew assembleDebug
```
O APK será gerado em:
```text
app/build/outputs/apk/debug/app-debug.apk
```
Instalação em um device / emulador / container Android:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
No Android (já em su): para ativar o servico e dar as permicoes
```sh
su

SERVICE="com.chris.uix/.UixAccessibilityService"

settings put secure enabled_accessibility_services "$SERVICE"
settings put secure accessibility_enabled 1
```
### PROJETO DESENVOLVDO POR CHRISTIAN RACHID VALEZI (A.K.A) DJ CHRIS NO BEAT



