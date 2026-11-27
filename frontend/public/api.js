import { config } from './config.js';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request(method, path, body) {
  const response = await fetch(config.apiBaseUrl + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new ApiError(response.status, data?.code ?? 'HTTP_' + response.status, data?.message ?? response.statusText);
  }
  return data;
}

// HTTP(비보안 주소)에서는 crypto.randomUUID가 없으므로 직접 만든다.
export function newClientOrderId() {
  if (globalThis.crypto?.randomUUID) {
    return crypto.randomUUID();
  }
  return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
}

export const api = {
  symbols: () => request('GET', '/api/symbols'),
  balance: (accountId) => request('GET', `/api/accounts/${accountId}/balance`),
  holdings: (accountId) => request('GET', `/api/accounts/${accountId}/holdings`),
  orders: (accountId) => request('GET', `/api/accounts/${accountId}/orders?size=${config.orderPageSize}`),
  orderBook: (symbolCode) =>
    request('GET', `/api/symbols/${encodeURIComponent(symbolCode)}/orderbook?depth=${config.orderBookDepth}`),
  placeOrder: (accountId, order) => request('POST', `/api/accounts/${accountId}/orders`, order),
};
