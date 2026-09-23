import { TimeoutError } from 'rxjs';
import { describe, expect, it } from 'vitest';

import {
  CHAT_REQUEST_TIMEOUT_MS,
  ChatBackendError,
  chatErrorMessage,
  extractSseFrames,
  toChatStreamEvent
} from './chat.service';

describe('chat request errors', () => {
  it('uses a bounded two-minute request timeout', () => {
    expect(CHAT_REQUEST_TIMEOUT_MS).toBe(120_000);
  });

  it('explains when local generation exceeds the timeout', () => {
    expect(chatErrorMessage(new TimeoutError())).toContain('2 minuti');
  });

  it('keeps a distinct message for communication failures', () => {
    expect(chatErrorMessage(new Error('offline'))).toContain('backend o Ollama');
  });

  it('shows the backend-provided message for in-stream errors', () => {
    expect(chatErrorMessage(new ChatBackendError('Ollama non in esecuzione.')))
      .toBe('Ollama non in esecuzione.');
  });
});

describe('extractSseFrames', () => {
  it('parses complete frames and keeps the incomplete tail', () => {
    const buffer =
      'event:delta\ndata:{"text":"Riavviare"}\n\n' +
      'event:delta\ndata:{"text":" lo spooler"}\n\n' +
      'event:done\ndata:{"generation';

    const { frames, rest } = extractSseFrames(buffer);

    expect(frames).toEqual([
      { event: 'delta', data: '{"text":"Riavviare"}' },
      { event: 'delta', data: '{"text":" lo spooler"}' }
    ]);
    expect(rest).toBe('event:done\ndata:{"generation');
  });

  it('handles CRLF newlines and the optional space after "data:"', () => {
    const { frames, rest } = extractSseFrames(
      'event:done\r\ndata: {"generationMillis":42}\r\n\r\n'
    );

    expect(frames).toEqual([{ event: 'done', data: '{"generationMillis":42}' }]);
    expect(rest).toBe('');
  });

  it('joins multi-line data blocks with newlines', () => {
    const { frames } = extractSseFrames('data:riga1\ndata:riga2\n\n');

    expect(frames).toEqual([{ event: 'message', data: 'riga1\nriga2' }]);
  });

  it('ignores blocks without data (comments, heartbeat)', () => {
    const { frames } = extractSseFrames(':heartbeat\n\n');

    expect(frames).toEqual([]);
  });
});

describe('toChatStreamEvent', () => {
  it('maps the sources frame with retrieval timing', () => {
    const event = toChatStreamEvent({
      event: 'sources',
      data: '{"sources":[{"id":"TCK-001","titolo":"VPN","categoria":"Rete","score":0.8}],"retrievalMillis":12}'
    });

    expect(event).toEqual({
      type: 'sources',
      sources: [{ id: 'TCK-001', titolo: 'VPN', categoria: 'Rete', score: 0.8 }],
      retrievalMillis: 12
    });
  });

  it('maps delta and done frames', () => {
    expect(toChatStreamEvent({ event: 'delta', data: '{"text":"Riavviare"}' }))
      .toEqual({ type: 'delta', text: 'Riavviare' });
    expect(toChatStreamEvent({ event: 'done', data: '{"generationMillis":7100}' }))
      .toEqual({ type: 'done', generationMillis: 7100 });
  });

  it('throws a ChatBackendError for error frames', () => {
    expect(() =>
      toChatStreamEvent({ event: 'error', data: '{"message":"Errore interno."}' })
    ).toThrow(ChatBackendError);
  });

  it('ignores unknown frame types', () => {
    expect(toChatStreamEvent({ event: 'ping', data: '{}' })).toBeNull();
  });
});
