import { ChatResponse } from './chat.service';

/** Chiave della vecchia cronologia a chat singola, letta solo per la migrazione. */
export const CHAT_HISTORY_STORAGE_KEY = 'rag-helpdesk.chat-history';
export const CONVERSATIONS_STORAGE_KEY = 'rag-helpdesk.conversations';

export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  response?: ChatResponse;
  feedback?: 'up' | 'down';
}

/** Una conversazione: le domande di una sessione e le risposte ricevute. */
export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
  messages: ChatMessage[];
}

export const NEW_CONVERSATION_TITLE = 'Nuova conversazione';

// Due conversazioni create nello stesso millisecondo avrebbero lo stesso id:
// il contatore garantisce l'unicità anche in quel caso.
let sequence = 0;

export function newConversation(): Conversation {
  sequence += 1;
  return {
    id: `c${Date.now().toString(36)}-${sequence}`,
    title: NEW_CONVERSATION_TITLE,
    createdAt: Date.now(),
    messages: []
  };
}

/** Titolo derivato dalla prima domanda, troncato per la lista laterale. */
export function conversationTitle(question: string): string {
  const clean = question.trim().replace(/\s+/g, ' ');
  return clean.length > 42 ? `${clean.slice(0, 42)}...` : clean;
}

/**
 * Legge le conversazioni dal browser. Se trova solo la vecchia cronologia
 * a chat singola la migra in una conversazione, così nulla va perso.
 * Restituisce sempre almeno una conversazione.
 */
export function readConversations(storage: Storage): Conversation[] {
  try {
    const stored = storage.getItem(CONVERSATIONS_STORAGE_KEY);
    if (stored) {
      const parsed: unknown = JSON.parse(stored);
      if (Array.isArray(parsed)) {
        const conversations = parsed.filter(isConversation).map(normalize);
        if (conversations.length > 0) {
          return conversations;
        }
      }
    }

    const legacy = storage.getItem(CHAT_HISTORY_STORAGE_KEY);
    if (legacy) {
      const parsed: unknown = JSON.parse(legacy);
      if (Array.isArray(parsed)) {
        const messages = parsed.filter(isChatMessage);
        if (messages.length > 0) {
          const first = messages.find((m) => m.role === 'user');
          const migrated: Conversation = {
            ...newConversation(),
            title: first ? conversationTitle(first.text) : 'Conversazione importata',
            messages
          };
          writeConversations(storage, [migrated]);
          return [migrated];
        }
      }
    }
  } catch {
    // Storage non disponibile o contenuto illeggibile: si riparte da zero.
  }

  return [newConversation()];
}

export function writeConversations(storage: Storage, conversations: Conversation[]): void {
  try {
    storage.setItem(CONVERSATIONS_STORAGE_KEY, JSON.stringify(conversations));
  } catch {
    // La chat rimane utilizzabile anche senza storage del browser.
  }
}

/** Completa una conversazione letta da storage con i campi eventualmente assenti. */
function normalize(conversation: Conversation): Conversation {
  return {
    ...conversation,
    createdAt: typeof conversation.createdAt === 'number' ? conversation.createdAt : Date.now()
  };
}

function isConversation(value: unknown): value is Conversation {
  if (!value || typeof value !== 'object') {
    return false;
  }

  const conversation = value as { id?: unknown; title?: unknown; messages?: unknown };
  return (
    typeof conversation.id === 'string' &&
    typeof conversation.title === 'string' &&
    Array.isArray(conversation.messages) &&
    conversation.messages.every(isChatMessage)
  );
}

function isChatMessage(value: unknown): value is ChatMessage {
  if (!value || typeof value !== 'object') {
    return false;
  }

  const message = value as { role?: unknown; text?: unknown };
  return (message.role === 'user' || message.role === 'assistant') && typeof message.text === 'string';
}
