# RAG Helpdesk

Assistente RAG per ticket di supporto tecnico: backend Spring Boot/Spring AI con
embedding ONNX locale e generazione tramite Ollama, più frontend Angular.

Il backend usa Spring AI **1.1.8**. Per evitare generazioni di durata
imprevedibile, `gemma4:e4b` viene eseguito senza thinking e con un massimo di
**384 token** per risposta.

Il dataset operativo è
`backend/src/main/resources/data/tickets_dataset.json`: contiene **38 ticket**.
La valutazione usa le **8 query** annotate in
`backend/src/main/resources/data/eval_queries.json`.

## Prerequisiti

Installare e rendere disponibili nel `PATH`:

- JDK **21 o successivo** (`java -version`);
- Node.js nelle versioni supportate da Angular 22:
  **`^22.22.3 || ^24.15.0 || >=26.0.0`**; su questa macchina è stato testato
  **Node 24.15.0**. Non usare Node 25. npm **11 o successivo**
  (`node --version`, `npm.cmd --version` in PowerShell);
- Ollama **0.32.1 o successivo** (`ollama --version`), con il servizio locale
  attivo sulla porta 11434;
- accesso a Internet al primo avvio.

Non servono installazioni globali di Maven né di Angular CLI: il backend usa il
Maven Wrapper incluso (Maven 3.9.11) e il frontend installa Angular CLI 22.0.7
come dipendenza locale con npm.

Prima di avviare il backend, verificare il modello richiesto:

```powershell
ollama list
ollama pull gemma4:e4b   # eseguire soltanto se gemma4:e4b non appare nell'elenco
```

Su macOS/Linux i comandi Ollama sono identici.

## Primo avvio (PowerShell)

Aprire **due terminali PowerShell** nella radice del progetto (`rag-helpdesk`).
Nel primo avvio è normale scaricare le dipendenze Maven/npm, il modello Ollama e
gli artefatti dell'embedding: circa **470 MB** per il modello ONNX e circa
**9 MB** per il tokenizer (all'incirca 479 MB complessivi), oltre alle altre
dipendenze. I file ONNX/tokenizer vengono memorizzati nella cache locale di
Spring AI e riusati dagli avvii successivi.

### Terminale 1 - backend (porta 8080)

```powershell
Set-Location .\backend
.\mvnw.cmd spring-boot:run
```

Il primo avvio indicizza i 38 ticket. Il processo salva la cache in
`backend/vector-store.json` e il fingerprint in
`backend/vector-store.json.sha256`.

### Terminale 2 - frontend (porta 4200)

```powershell
Set-Location .\frontend
npm.cmd ci
npm.cmd start
```

Aprire [http://localhost:4200](http://localhost:4200). Il frontend invia le
richieste al backend su `http://localhost:8080/api/chat`; il CORS di sviluppo
accetta l'origine `http://localhost:4200`.

Il carattere dell'interfaccia (Archivo) viene caricato da Google Fonts. Senza
rete l'app funziona lo stesso: si ricade sul carattere di sistema.

### Cronologia della chat

Le conversazioni (domande, risposte, fonti citate e feedback)  vengono salvate
automaticamente nel `localStorage` del browser, sotto la chiave
`rag-helpdesk.conversations`. La cronologia resta disponibile dopo il refresh
della pagina, ma rimane solo su quel browser e su quel dispositivo: non viene
inviata al backend. Per cancellarla, eliminare i dati del sito nel browser.

Chi arriva da una versione precedente non perde nulla: la vecchia cronologia a
chat singola (`rag-helpdesk.chat-history`) viene migrata automaticamente in una
conversazione al primo avvio.

Non eseguire `ng new`: `frontend` è già un workspace Angular completo. Non
occorre installare `@angular/cli` globalmente.

## Equivalenti macOS/Linux

Dalla radice del progetto, in due terminali distinti:

```bash
# Terminale backend
cd backend && ./mvnw spring-boot:run
```

```bash
# Terminale frontend
cd frontend && npm ci && npm start
```

## Verifica API (PowerShell)

Quando il backend ha completato l'avvio, da qualunque cartella eseguire:

```powershell
Invoke-RestMethod http://localhost:8080/api/eval

Invoke-RestMethod http://localhost:8080/api/chat `
  -Method Post `
  -ContentType 'application/json' `
  -Body '{"question":"La VPN non si connette da casa"}'
```

`/api/eval` restituisce le metriche di retrieval per 8 query. `/api/chat`
restituisce testo, fonti e i campi `retrievalMillis` e `generationMillis`.
`/api/chat/stream` (stesso corpo JSON) restituisce gli stessi dati come
Server-Sent Events: un evento `sources` con fonti e tempo di retrieval, una
serie di eventi `delta` con i frammenti di testo generati e un evento `done`
conclusivo con il tempo di generazione. È l'endpoint usato dal frontend: il
primo frammento arriva dopo 1-2 secondi a caldo, senza attendere l'intera
generazione.

All'avvio il backend precarica il modello in Ollama (warm-up) e con
`keep_alive: -1m` il modello resta residente in memoria senza mai essere
scaricato (contesto 4096 token): la prima domanda dell'utente non paga più il
caricamento dei pesi. Su una RTX 2070 il controllo del 23 luglio 2026 ha
misurato circa **33 secondi** a freddo (solo se il warm-up non è ancora
terminato o Ollama è stato riavviato) e **8 secondi** a caldo per la domanda
sulla VPN. I tempi dipendono dall'hardware e dalla lunghezza della risposta.

Il frontend interrompe l'attesa dopo **120 secondi** e mostra un errore
esplicito. Ricaricare la pagina durante una generazione può chiudere la
richiesta del browser mentre Ollama sta ancora terminando il lavoro già
avviato; attendere il completamento o il timeout prima di reinviare la domanda.

Per Bash, lo stesso controllo di valutazione è:

```bash
curl http://localhost:8080/api/eval
```

## Test e build

Eseguire questi comandi dalle directory indicate.

```powershell
# Da rag-helpdesk\backend
.\mvnw.cmd clean test

# Da rag-helpdesk\frontend
npm.cmd ci
npm.cmd test
npm.cmd run build
```

Le equivalenze macOS/Linux sono `./mvnw clean test` nella directory `backend` e
`npm ci && npm test && npm run build` nella directory `frontend`. L'output
della build Angular viene creato in `frontend/dist/`.

## Cache vettoriale e reindicizzazione

Ad ogni avvio il backend calcola SHA-256 dei byte di
`tickets_dataset.json`. Riutilizza il vector store solo quando il fingerprint
coincide con il file fratello `vector-store.json.sha256`; se il dataset cambia,
la cache viene automaticamente ignorata e i 38 ticket vengono reindicizzati.

Se cambia il modello o la configurazione di embedding, forzare la
reindicizzazione eliminando **entrambi** i file dalla radice del progetto,
quindi riavviare il backend:

```powershell
Remove-Item -Force -ErrorAction SilentlyContinue `
  .\backend\vector-store.json, .\backend\vector-store.json.sha256
```

Su macOS/Linux:

```bash
rm -f backend/vector-store.json backend/vector-store.json.sha256
```

## Porte e risoluzione dei problemi

| Sintomo | Controllo/azione |
| --- | --- |
| `gemma4:e4b` non disponibile o errore 500 in chat | Eseguire `ollama list`, poi `ollama pull gemma4:e4b`; verificare che Ollama sia attivo su `http://localhost:11434`. |
| Risposta JSON di errore dal backend | `POST /api/chat` restituisce HTTP 400 se `question` è vuota o supera 2000 caratteri. Per un errore interno restituisce HTTP 500 con `{ "error": "Errore interno. Verificare che Ollama sia in esecuzione." }`; controllare i log del backend per la causa effettiva. |
| Porta 8080, 4200 o 11434 già occupata | In PowerShell: `Get-NetTCPConnection -State Listen \| Where-Object { $_.LocalPort -in 8080,4200,11434 }`. Arrestare o riconfigurare soltanto il processo noto che usa la porta. Su macOS/Linux: `lsof -nP -iTCP:8080 -sTCP:LISTEN` (sostituire la porta se necessario). |
| Download iniziale non riesce | Controllare Internet, proxy/firewall e spazio disco; poi ripetere il comando di avvio. |
| Errore npm o Angular | Dalla directory `frontend`, usare Node/npm alle versioni minime indicate e rieseguire `npm.cmd ci` in PowerShell. |
| Frontend aperto ma chiamate bloccate | Avviare il backend su 8080 e il frontend su 4200: l'origine CORS configurata è `http://localhost:4200`. Il frontend termina l'attesa dopo 120 secondi; evitare di ricaricare e reinviare ripetutamente mentre Ollama sta generando. |
| Serve una reindicizzazione pulita | Eliminare entrambi `backend/vector-store.json` e `backend/vector-store.json.sha256` con il comando della sezione precedente. |

`backend/target/`, la cache vettoriale, `frontend/node_modules/`,
`frontend/dist/`, `frontend/.angular/` e i log locali sono esclusi dal controllo
versione tramite `.gitignore`.
