// 환경별 설정. 프론트를 다른 주소에서 띄우게 되면 apiBaseUrl에 백엔드 주소를 넣는다.
export const config = {
  apiBaseUrl: '',
  orderBookPollMs: 1000,
  accountRefreshDelayMs: 100,
  notificationListSize: 20,
  defaultAccountId: 1,
  orderPageSize: 20,
  orderBookDepth: 10,
};
