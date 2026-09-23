import { ChangeDetectorRef, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { ChatService, RetrievedTicket, chatErrorMessage } from './chat.service';
import {
  ChatMessage,
  Conversation,
  conversationTitle,
  newConversation as createConversation,
  readConversations,
  writeConversations
} from './chat-history.storage';

/** Un pezzo di risposta: testo semplice (citation 0) o un riferimento numerato a una fonte. */
export interface AnswerSegment {
  text: string;
  citation: number;
}

export const SUGGESTED_QUESTIONS = [
  'La VPN non si connette da casa',
  'La password del dominio è scaduta',
  'La casella di posta è piena',
  'La stampante non stampa'
];

/** Sotto questa larghezza la lista conversazioni diventa un pannello a scomparsa. */
export const NARROW_BREAKPOINT_PX = 820;

/**
 * Spezza la risposta sugli ID dei ticket citati dal modello e li sostituisce con
 * il numero della fonte corrispondente. Se il modello non cita nulla il testo
 * resta intatto: nessun riferimento viene inventato.
 */
export function splitCitations(answer: string, sources: RetrievedTicket[]): AnswerSegment[] {
  if (sources.length === 0) {
    return [{ text: answer, citation: 0 }];
  }

  const numberById = new Map(sources.map((s, i) => [s.id.toUpperCase(), i + 1]));
  const pattern = new RegExp(`(${sources.map((s) => escapeRegExp(s.id)).join('|')})`, 'gi');

  const segments: AnswerSegment[] = [];
  let last = 0;
  for (const match of answer.matchAll(pattern)) {
    const at = match.index ?? 0;
    if (at > last) {
      segments.push({ text: answer.slice(last, at), citation: 0 });
    }
    segments.push({ text: '', citation: numberById.get(match[0].toUpperCase()) ?? 0 });
    last = at + match[0].length;
  }
  if (last < answer.length) {
    segments.push({ text: answer.slice(last), citation: 0 });
  }
  return segments;
}

/** Dal testo indicizzato del ticket tiene solo la parte dopo "Soluzione:". */
export function ticketSolution(source: RetrievedTicket): string {
  const text = source.estratto ?? '';
  const at = text.indexOf('Soluzione:');
  return at >= 0 ? text.slice(at + 'Soluzione:'.length).trim() : text.trim();
}

/**
 * Interfaccia di chat: conversazioni multiple, stati progressivi della pipeline,
 * risposta con riferimenti numerati e schede dei ticket recuperati.
 */
@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat.component.html',
  styleUrls: ['./chat.component.css']
})
export class ChatComponent implements OnInit, OnDestroy {
  conversations: Conversation[] = [];
  activeId = '';
  question = '';
  loading = false;
  /** true dal primo token ricevuto: nasconde lo scheletro della risposta. */
  answerStarted = false;
  error = '';
  copied = false;
  narrow = false;
  sidebarOpen = false;

  /** Stato della pipeline mostrato durante l'attesa. */
  stage: 'idle' | 'searching' | 'writing' = 'idle';
  pendingSources: RetrievedTicket[] = [];
  pendingRetrievalMillis = 0;

  /** Esito dell'ultima richiesta: la spia in fondo alla sidebar non mente. */
  connection: 'unknown' | 'ok' | 'down' = 'unknown';

  readonly suggestions = SUGGESTED_QUESTIONS;
  // Allineati a rag.top-k e rag.similarity-threshold in application.yml
  readonly topK = 4;
  readonly threshold = '0,50';

  /** Schede fonte aperte, per "idConversazione#indiceMessaggio". */
  private readonly openSources = new Map<string, Set<number>>();

  // L'app è zoneless: gli aggiornamenti che arrivano dallo stream (fuori da
  // eventi DOM) vanno notificati esplicitamente al change detection.
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly onResize = (): void => {
    const narrow = window.innerWidth < NARROW_BREAKPOINT_PX;
    if (narrow !== this.narrow) {
      this.narrow = narrow;
      this.sidebarOpen = false;
      this.changeDetector.markForCheck();
    }
  };

  constructor(private chatService: ChatService) {}

  ngOnInit(): void {
    this.conversations = readConversations(localStorage);
    this.activeId = this.conversations[0].id;
    this.onResize();
    window.addEventListener('resize', this.onResize);
  }

  ngOnDestroy(): void {
    window.removeEventListener('resize', this.onResize);
  }

  get activeConversation(): Conversation {
    return this.conversations.find((c) => c.id === this.activeId) ?? this.conversations[0];
  }

  get messages(): ChatMessage[] {
    return this.activeConversation.messages;
  }

  get connectionLabel(): string {
    switch (this.connection) {
      case 'ok':
        return 'backend · ollama attivi';
      case 'down':
        return 'backend non raggiungibile';
      default:
        return 'backend · nessuna richiesta';
    }
  }

  get lastAnswer(): ChatMessage | undefined {
    return [...this.messages].reverse().find((m) => m.role === 'assistant' && !!m.response);
  }

  conversationMeta(conversation: Conversation): string {
    const questions = conversation.messages.filter((m) => m.role === 'user').length;
    if (questions === 0) {
      return 'vuota';
    }

    const sources = conversation.messages.reduce((n, m) => n + (m.response?.sources.length ?? 0), 0);
    return `${questions} ${questions === 1 ? 'domanda' : 'domande'} · ${sources} fonti`;
  }

  newConversation(): void {
    const conversation = createConversation();
    this.conversations = [conversation, ...this.conversations];
    this.selectConversation(conversation.id);
    this.persist();
  }

  selectConversation(id: string): void {
    this.activeId = id;
    this.sidebarOpen = false;
    this.copied = false;
    this.error = '';
  }

  askSuggestion(text: string): void {
    this.question = text;
    this.send();
  }

  send(): void {
    const q = this.question.trim();
    if (!q || this.loading) {
      return;
    }

    // Riferimento catturato: se l'utente cambia conversazione mentre il modello
    // scrive, la risposta continua ad arrivare in quella da cui è partita.
    const conversation = this.activeConversation;
    conversation.messages.push({ role: 'user', text: q });
    if (conversation.messages.filter((m) => m.role === 'user').length === 1) {
      conversation.title = conversationTitle(q);
    }

    this.question = '';
    this.loading = true;
    this.answerStarted = false;
    this.copied = false;
    this.error = '';
    this.stage = 'searching';
    this.pendingSources = [];
    this.pendingRetrievalMillis = 0;
    this.persist();

    let sources: RetrievedTicket[] = [];
    let retrievalMillis = 0;
    let assistant: ChatMessage | null = null;

    this.chatService
      .askStream(q)
      .pipe(
        finalize(() => {
          this.loading = false;
          this.answerStarted = false;
          this.stage = 'idle';
          this.changeDetector.markForCheck();
        })
      )
      .subscribe({
        next: (event) => {
          switch (event.type) {
            case 'sources':
              sources = event.sources;
              retrievalMillis = event.retrievalMillis;
              this.pendingSources = sources;
              this.pendingRetrievalMillis = retrievalMillis;
              this.stage = 'writing';
              this.connection = 'ok';
              break;
            case 'delta':
              if (!assistant) {
                assistant = { role: 'assistant', text: '' };
                conversation.messages.push(assistant);
                this.answerStarted = true;
              }
              assistant.text += event.text;
              break;
            case 'done':
              if (assistant) {
                assistant.response = {
                  answer: assistant.text,
                  sources,
                  retrievalMillis,
                  generationMillis: event.generationMillis
                };
              }
              // La cronologia viene salvata una sola volta, a risposta completa
              this.persist();
              break;
          }
          this.changeDetector.markForCheck();
        },
        error: (error: unknown) => {
          this.error = chatErrorMessage(error);
          // Un errore prima delle fonti significa backend o Ollama irraggiungibili
          if (this.connection !== 'ok' || sources.length === 0) {
            this.connection = 'down';
          }
          // L'eventuale risposta parziale resta visibile e viene conservata
          this.persist();
          this.changeDetector.markForCheck();
        }
      });
  }

  segments(message: ChatMessage): AnswerSegment[] {
    return splitCitations(message.text, message.response?.sources ?? []);
  }

  citationTitle(message: ChatMessage, citation: number): string {
    const source = message.response?.sources[citation - 1];
    return source ? `${source.id} - ${source.titolo}` : '';
  }

  solutionOf(source: RetrievedTicket): string {
    return ticketSolution(source);
  }

  /** Un ticket recuperato è "citato" se il modello ne ha nominato l'ID nella risposta. */
  isCited(message: ChatMessage, source: RetrievedTicket): boolean {
    return message.text.toUpperCase().includes(source.id.toUpperCase());
  }

  isOpen(messageIndex: number, sourceIndex: number): boolean {
    const open = this.openSources.get(this.openKey(messageIndex));
    // Senza interazione dell'utente resta aperta solo la fonte più simile
    return open ? open.has(sourceIndex) : sourceIndex === 0;
  }

  toggleSource(messageIndex: number, sourceIndex: number): void {
    const open = this.openSet(messageIndex);
    if (open.has(sourceIndex)) {
      open.delete(sourceIndex);
    } else {
      open.add(sourceIndex);
    }
  }

  openSource(messageIndex: number, sourceIndex: number): void {
    this.openSet(messageIndex).add(sourceIndex);
  }

  vote(message: ChatMessage, feedback: 'up' | 'down'): void {
    message.feedback = message.feedback === feedback ? undefined : feedback;
    this.persist();
  }

  copyAnswer(): void {
    const answer = this.lastAnswer;
    if (!answer) {
      return;
    }

    void navigator.clipboard?.writeText(answer.text);
    this.copied = true;
  }

  private openSet(messageIndex: number): Set<number> {
    const key = this.openKey(messageIndex);
    const open = this.openSources.get(key) ?? new Set<number>([0]);
    this.openSources.set(key, open);
    return open;
  }

  private openKey(messageIndex: number): string {
    return `${this.activeId}#${messageIndex}`;
  }

  private persist(): void {
    writeConversations(localStorage, this.conversations);
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
