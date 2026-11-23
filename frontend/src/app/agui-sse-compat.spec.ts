import {HttpAgent} from '@ag-ui/client';
import {describe, expect, it} from 'vitest';

describe('AG-UI SSE framing', () => {
  for (const separator of ['', ' ']) {
    it(`preserves a leading-space delta with data delimiter ${JSON.stringify(separator)}`, async () => {
      const events = [
        {type:'RUN_STARTED', threadId:'thread', runId:'run'},
        {type:'TEXT_MESSAGE_START', messageId:'message', role:'assistant'},
        {type:'TEXT_MESSAGE_CONTENT', messageId:'message', delta:'  leading'},
        {type:'TEXT_MESSAGE_END', messageId:'message'},
        {type:'RUN_FINISHED', threadId:'thread', runId:'run'}
      ];
      const wire = events.map(event => `data:${separator}${JSON.stringify(event)}\n\n`).join('');
      const agent = new HttpAgent({url:'/api/ag-ui', threadId:'thread', fetch:async () =>
        new Response(wire, {headers:{'Content-Type':'text/event-stream'}})});
      const deltas: string[] = [];
      await agent.runAgent({runId:'run'}, {onTextMessageContentEvent: ({event}) => { deltas.push(event.delta); }});
      expect(deltas).toEqual(['  leading']);
    });
  }
});
