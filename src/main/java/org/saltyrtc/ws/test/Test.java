package org.saltyrtc.ws.test;

import com.neovisionaries.ws.client.ProxySettings;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.neovisionaries.ws.client.WebSocketState;

import java.net.URI;
import java.util.List;
import java.util.Map;

public class Test {
    public static void main(String[] args) throws Exception {
        WebSocketFactory factory = new WebSocketFactory();

        // Apply settings
        URI uri = new URI("wss://server.saltyrtc.org/debc3a6c9a630f27eae6bc3fd962925bdeb63844c09103f609bf7082bc383610");
        int timeout = 10000;
//        ProxySettings proxySettings = factory.getProxySettings();
//        proxySettings.setServer("https://proxy.saltyrtc.org:3128");

        // Create WebSocket
        WebSocket ws = factory.createSocket(uri, timeout);
        ws.addProtocol("v1.saltyrtc.org");
        ws.addListener(new WebSocketAdapter() {
            public void onStateChanged(WebSocket websocket, WebSocketState newState) throws Exception {
                System.out.println("onStateChanged: " + newState);
            }

            public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {
                System.out.println("onConnected");
            }

            public void onConnectError(WebSocket websocket, WebSocketException cause) throws Exception {
                System.out.println("onConnectError: " + cause);
            }

            public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
                System.out.println("onDisconnected");
            }

            public void onFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
                System.out.println("onFrame: " + frame);
            }

            public void onTextMessage(WebSocket websocket, String text) throws Exception {
                System.out.println("onTextMessage: " + text);
            }

            public void onBinaryMessage(WebSocket websocket, byte[] binary) throws Exception {
                System.out.println("onBinaryMessage: " + binary.length);
            }

            public void onError(WebSocket websocket, WebSocketException cause) throws Exception {
                System.out.println("onError: " + cause);
            }

            public void onSendError(WebSocket websocket, WebSocketException cause, WebSocketFrame frame) throws Exception {
                System.out.println("onSendError: " + cause);
            }

            public void onUnexpectedError(WebSocket websocket, WebSocketException cause) throws Exception {
                System.out.println("onUnexpectedError: " + cause);
            }

            public void handleCallbackError(WebSocket websocket, Throwable cause) throws Exception {
                System.out.println("handleCallbackError: " + cause);
            }
        });

        // Connect
        ws.connect();

        // Close after 1s
        Thread.sleep(1000);
        ws.sendClose();
    }
}
