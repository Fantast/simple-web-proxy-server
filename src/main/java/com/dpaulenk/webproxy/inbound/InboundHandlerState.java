package com.dpaulenk.webproxy.inbound;

public enum InboundHandlerState {
    INITIAL,
    WAITING_OUTBOUND_CONNECTION,
    WAITING_ESTEBLISHED_RESPONSE,
    READING_CONTENT,
    DISCONNECTED
}
