import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, TimeoutError, timeout } from 'rxjs';

export const CHAT_REQUEST_TIMEOUT_MS = 120_000;

/** Errore applicativo segnalato dal backend dentro lo stream SSE. */
export class ChatBackendError extends Error {}

export function chatErrorMessage(error: unknown): string {
  if (error instanceof TimeoutError) {
    return 'La risposta ha superato 2 minuti. Riprova con una domanda più breve.';
  }

  if (error instanceof ChatBackendError) {
    return error.message;
  }

  return 'Errore di comunicazione con il backend o Ollama.';
}

export interface RetrievedTicket {
  id: string;
  titolo: string;
  categoria: string;
  score: number;
  priorita?: string;
  /** Testo indicizzato del ticket: la UI ne mostra la parte "Soluzione:". */
  estratto?: string;
}

export interface ChatResponse {
  answer: string;
  sources: RetrievedTicket[];
  retrievalMillis: number;
  generationMillis: number;
}

/** Eventi emessi da POST /api/chat/stream, nell'ordine: sources, delta*, done. */
export type ChatStreamEvent =
  | { type: 'sources'; sources: RetrievedTicket[]; retrievalMillis: number }
  | { type: 'delta'; text: string }
  | { type: 'done'; generationMillis: number };

/** Un frame Server-Sent Events già delimitato (righe "event:" e "data:"). */
export interface SseFrame {
  event: string;
  data: string;
}

/**
 * Estrae dal buffer i frame SSE completi (terminati da riga vuota) e restituisce
 * la coda ancora incompleta, da mantenere per il prossimo chunk di rete.
 */
export function extractSseFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const blocks = buffer.replace(/\r\n/g, '\n').split('\n\n');
  const rest = blocks.pop() ?? '';

  const frames: SseFrame[] = [];
  for (const block of blocks) {
    let event = 'message';
    const dataLines: string[] = [];

    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice('event:'.length).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice('data:'.length).replace(/^ /, ''));
      }
    }

    if (dataLines.length > 0) {
      frames.push({ event, data: dataLines.join('\n') });
    }
  }

  return { frames, rest };
}

/**
 * Converte un frame SSE del backend in un ChatStreamEvent tipizzato.
 * Un frame "error" diventa un'eccezione; i frame sconosciuti vengono ignorati.
 */
export function toChatStreamEvent(frame: SseFrame): ChatStreamEvent | null {
  const payload: unknown = JSON.parse(frame.data);
  const record = (payload ?? {}) as Record<string, unknown>;

  switch (frame.event) {
    case 'sources':
      return {
        type: 'sources',
        sources: Array.isArray(record['sources']) ? (record['sources'] as RetrievedTicket[]) : [],
        retrievalMillis: typeof record['retrievalMillis'] === 'number' ? record['retrievalMillis'] : 0
      };
    case 'delta':
      return { type: 'delta', text: typeof record['text'] === 'string' ? record['text'] : '' };
    case 'done':
      return {
        type: 'done',
        generationMillis: typeof record['generationMillis'] === 'number' ? record['generationMillis'] : 0
      };
    case 'error':
      throw new ChatBackendError(
        typeof record['message'] === 'string' ? record['message'] : 'Errore interno del backend.'
      );
    default:
      return null;
  }
}

/** Servizio che comunica con il backend Spring Boot. */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly apiUrl = 'http://localhost:8080/api/chat';
  private readonly streamUrl = 'http://localhost:8080/api/chat/stream';

  constructor(private http: HttpClient) {}

  ask(question: string): Observable<ChatResponse> {
    return this.http
      .post<ChatResponse>(this.apiUrl, { question })
      .pipe(timeout({ first: CHAT_REQUEST_TIMEOUT_MS }));
  }

  /**
   * Variante in streaming: la risposta arriva token per token via SSE.
   * Il timeout vale sia per il primo evento sia per ogni intervallo di
   * silenzio successivo, così una generazione bloccata viene interrotta.
   */
  askStream(question: string): Observable<ChatStreamEvent> {
    const stream = new Observable<ChatStreamEvent>((observer) => {
      const controller = new AbortController();

      fetch(this.streamUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          // application/json in coda: le risposte di errore (es. 400) non sono SSE
          Accept: 'text/event-stream, application/json'
        },
        body: JSON.stringify({ question }),
        signal: controller.signal
      })
        .then(async (response) => {
          if (!response.ok || !response.body) {
            throw new Error(`Risposta HTTP ${response.status} dal backend`);
          }

          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';

          for (;;) {
            const { value, done } = await reader.read();
            if (done) {
              break;
            }

            buffer += decoder.decode(value, { stream: true });
            const { frames, rest } = extractSseFrames(buffer);
            buffer = rest;

            for (const frame of frames) {
              const event = toChatStreamEvent(frame); // un frame "error" lancia qui
              if (event) {
                observer.next(event);
              }
            }
          }

          observer.complete();
        })
        .catch((error: unknown) => observer.error(error));

      return () => controller.abort();
    });

    return stream.pipe(
      timeout({ first: CHAT_REQUEST_TIMEOUT_MS, each: CHAT_REQUEST_TIMEOUT_MS })
    );
  }
}
