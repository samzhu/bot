export interface SseBusinessEvent {
  event: string;
  data: any;
  rawText: string;
}

export class SseParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SseParseError';
  }
}

export class SseValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SseValidationError';
  }
}

export class SsePrematureEofError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SsePrematureEofError';
  }
}

export interface SseParserCallbacks {
  onEvent: (event: SseBusinessEvent) => void;
  onError?: (error: Error) => void;
}

export class SseStreamParser {
  private decoder = new TextDecoder('utf-8');
  private buffer = '';
  private currentEventName = 'message';
  private currentDataLines: string[] = [];

  private expectedRunId: string | null = null;
  private lastSeq = 0;
  private terminalCount = 0;
  private hasReceivedStarted = false;
  private isClosed = false;
  private callbacks: SseParserCallbacks;

  constructor(callbacks: SseParserCallbacks) {
    this.callbacks = callbacks;
  }

  /**
   * Feed a raw Uint8Array chunk or string chunk into the streaming parser.
   * Handles multi-byte UTF-8 character splitting and CRLF boundary splitting across chunks.
   */
  public feed(chunk: Uint8Array | string): void {
    if (this.isClosed) {
      throw new SseValidationError('Cannot feed data to closed SseStreamParser');
    }
    let text: string;
    if (typeof chunk === 'string') {
      text = chunk;
    } else {
      text = this.decoder.decode(chunk, { stream: true });
    }
    this.buffer += text;
    this.processLines();
  }

  private processLines(): void {
    let pos = 0;
    while (pos < this.buffer.length) {
      const ch = this.buffer[pos];

      if (ch === '\r') {
        // If \r is the last character in buffer, wait for next chunk to determine if it's \r\n
        if (pos + 1 >= this.buffer.length) {
          break;
        }
        const line = this.buffer.substring(0, pos);
        if (this.buffer[pos + 1] === '\n') {
          this.buffer = this.buffer.substring(pos + 2);
        } else {
          this.buffer = this.buffer.substring(pos + 1);
        }
        pos = 0;
        this.handleLine(line);
      } else if (ch === '\n') {
        const line = this.buffer.substring(0, pos);
        this.buffer = this.buffer.substring(pos + 1);
        pos = 0;
        this.handleLine(line);
      } else {
        pos++;
      }
    }
  }

  private handleLine(line: string): void {
    // Blank line indicates dispatch of accumulated event block
    if (line.length === 0) {
      this.dispatchCurrentEvent();
      return;
    }

    // Comment line: strictly ignored per SSE spec
    if (line.startsWith(':')) {
      return;
    }

    if (line.startsWith('event:')) {
      this.currentEventName = line.substring(6).trim();
    } else if (line.startsWith('data:')) {
      let val = line.substring(5);
      if (val.startsWith(' ')) {
        val = val.substring(1);
      }
      this.currentDataLines.push(val);
    }
  }

  private dispatchCurrentEvent(): void {
    if (this.currentDataLines.length === 0) {
      this.currentEventName = 'message';
      return;
    }

    const rawData = this.currentDataLines.join('\n');
    const eventName = this.currentEventName;

    // Reset current event accumulator before dispatch
    this.currentEventName = 'message';
    this.currentDataLines = [];

    let parsedData: any;
    try {
      parsedData = JSON.parse(rawData);
    } catch (e: any) {
      const parseErr = new SseParseError(`Corrupted JSON in SSE data: ${rawData}`);
      if (this.callbacks.onError) this.callbacks.onError(parseErr);
      throw parseErr;
    }

    this.validateBusinessEvent(eventName, parsedData, rawData);

    this.callbacks.onEvent({
      event: eventName,
      data: parsedData,
      rawText: rawData
    });
  }

  private validateBusinessEvent(eventName: string, data: any, rawData: string): void {
    // 1. Any business event after terminal is strictly forbidden
    if (this.terminalCount > 0) {
      const err = new SseValidationError(`Received business event '${eventName}' after terminal event`);
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }

    if (!data || typeof data !== 'object') {
      const err = new SseValidationError(`Invalid SSE data payload: ${rawData}`);
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }

    const runId = data.runId;
    const seq = data.seq;

    // 2. Validate runId is non-blank string
    if (typeof runId !== 'string' || runId.trim().length === 0) {
      const err = new SseValidationError(`Missing or invalid runId in event '${eventName}': ${rawData}`);
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }

    // 3. Validate seq is positive integer
    if (typeof seq !== 'number' || !Number.isInteger(seq) || seq < 1) {
      const err = new SseValidationError(`Invalid seq in event '${eventName}', must be positive integer: ${rawData}`);
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }

    // 4. Handle run.started
    if (eventName === 'run.started') {
      if (this.hasReceivedStarted) {
        const err = new SseValidationError(`Duplicate run.started event received`);
        if (this.callbacks.onError) this.callbacks.onError(err);
        throw err;
      }
      if (seq !== 1) {
        const err = new SseValidationError(`run.started must have seq 1, received ${seq}`);
        if (this.callbacks.onError) this.callbacks.onError(err);
        throw err;
      }
      this.expectedRunId = runId;
      this.lastSeq = 1;
      this.hasReceivedStarted = true;
      return;
    }

    // 5. Must have received run.started first
    if (!this.hasReceivedStarted) {
      const err = new SseValidationError(`Received event '${eventName}' before run.started`);
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }

    // 6. Strict runId equality
    if (runId !== this.expectedRunId) {
      const err = new SseValidationError(`Mismatched runId in event '${eventName}': expected ${this.expectedRunId}, got ${runId}`);
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }

    // 7. Strict strictly increasing seq
    if (seq !== this.lastSeq + 1) {
      const err = new SseValidationError(`Sequence gap in event '${eventName}': expected ${this.lastSeq + 1}, got ${seq}`);
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }
    this.lastSeq = seq;

    // 8. Terminal events
    if (eventName === 'run.completed' || eventName === 'run.cancelled' || eventName === 'run.failed') {
      this.terminalCount = 1;
    }
  }

  /**
   * Signal end-of-stream (EOF).
   * Verifies exactly 1 terminal event occurred, and no truncated trailing frame is left.
   */
  public finish(): void {
    this.isClosed = true;
    const remainingText = this.decoder.decode(new Uint8Array(), { stream: false });
    if (remainingText) {
      this.buffer += remainingText;
    }
    this.processLines();

    // Check premature EOF
    if (this.terminalCount === 0) {
      const err = new SsePrematureEofError('Stream terminated prematurely before terminal event');
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }

    // If terminal was reached, check if there is unfinished non-whitespace trailing data
    if (this.buffer.trim().length > 0 || this.currentDataLines.length > 0) {
      const err = new SseValidationError(`Abnormal trailing content after terminal event: ${this.buffer}`);
      if (this.callbacks.onError) this.callbacks.onError(err);
      throw err;
    }
  }

  public getTerminalCount(): number {
    return this.terminalCount;
  }

  public getLastSeq(): number {
    return this.lastSeq;
  }

  public getExpectedRunId(): string | null {
    return this.expectedRunId;
  }
}
