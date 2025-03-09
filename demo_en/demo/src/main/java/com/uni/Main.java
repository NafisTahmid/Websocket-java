package com.uni;

import com.uni.Websocketserver.Websocket;

public class Main {
    public static void main(String[] args) {
        String ip = "localhost"; // Use localhost or a valid IP address
        int port = 8089; // Use a different port if 8089 is occupied
        try {
            new Websocket().run(ip, port);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("WebSocket server startup failed, please check if the port is occupied");
        }
    }
}