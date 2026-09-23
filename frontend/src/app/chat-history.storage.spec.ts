import { beforeEach, describe, expect, it } from 'vitest';
import {
  CHAT_HISTORY_STORAGE_KEY,
  CONVERSATIONS_STORAGE_KEY,
  conversationTitle,
  newConversation,
  readConversations,
  writeConversations,
  type ChatMessage,
  type Conversation
} from './chat-history.storage';

const messages: ChatMessage[] = [
  { role: 'user', text: 'Quali ticket sono urgenti?' },
  {
    role: 'assistant',
    text: 'Il ticket T-1006 è urgente.',
    feedback: 'up',
    response: {
      answer: 'Il ticket T-1006 è urgente.',
      sources: [],
      retrievalMillis: 12,
      generationMillis: 3000
    }
  }
];

describe('chat history storage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('restores conversations saved in the browser', () => {
    const conversation: Conversation = { ...newConversation(), title: 'VPN', messages };

    writeConversations(localStorage, [conversation]);

    expect(readConversations(localStorage)).toEqual([conversation]);
  });

  it('starts from one empty conversation when the browser has nothing stored', () => {
    const conversations = readConversations(localStorage);

    expect(conversations).toHaveLength(1);
    expect(conversations[0].messages).toEqual([]);
  });

  it('ignores corrupt browser data without breaking the chat', () => {
    localStorage.setItem(CONVERSATIONS_STORAGE_KEY, '{not valid json');

    expect(readConversations(localStorage)).toHaveLength(1);
  });

  it('migrates the single-chat history of the previous version', () => {
    localStorage.setItem(CHAT_HISTORY_STORAGE_KEY, JSON.stringify(messages));

    const conversations = readConversations(localStorage);

    expect(conversations).toHaveLength(1);
    expect(conversations[0].messages).toEqual(messages);
    expect(conversations[0].title).toBe('Quali ticket sono urgenti?');
    // La migrazione viene salvata: al riavvio si legge già il formato nuovo
    expect(localStorage.getItem(CONVERSATIONS_STORAGE_KEY)).not.toBeNull();
  });

  it('truncates long questions used as conversation title', () => {
    const title = conversationTitle('  La VPN   non si connette da casa e nemmeno dall ufficio  ');

    expect(title).toBe('La VPN non si connette da casa e nemmeno d...');
    expect(title.length).toBe(43);
  });

  it('gives every new conversation a distinct id', () => {
    expect(newConversation().id).not.toBe(newConversation().id);
  });
});
