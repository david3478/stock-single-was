import { config } from './config.js';

const TYPES = ['ORDER_OPEN', 'ORDER_PARTIALLY_FILLED', 'ORDER_FILLED'];

// 계좌 알림 구독(SSE). EventSource는 끊기면 스스로 재연결하면서 마지막으로 받은 이벤트 id를
// Last-Event-ID 헤더로 보내므로, 서버가 그 다음 알림부터 다시 보낸다.
// onStatus: 'open' | 'reconnecting' | 'closed'(없는 계좌 등으로 재연결하지 않음)
export function subscribeNotifications(accountId, { onNotification, onStatus }) {
  const source = new EventSource(`${config.apiBaseUrl}/api/accounts/${accountId}/notifications`);
  source.onopen = () => onStatus('open');
  source.onerror = () => onStatus(source.readyState === EventSource.CLOSED ? 'closed' : 'reconnecting');
  for (const type of TYPES) {
    source.addEventListener(type, (event) =>
      onNotification({ id: Number(event.lastEventId), type, ...JSON.parse(event.data) }),
    );
  }
  return () => source.close();
}
