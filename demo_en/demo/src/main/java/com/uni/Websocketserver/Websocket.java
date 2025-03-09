package com.uni.Websocketserver;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

public class Websocket {

    public void run(String ip, int port) {
        System.out.println("Starting websocket server on " + ip + ":" + port + "...");

        // Main thread group
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        // Work Thread Group
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Channel channel = null; // Initialize channel to null

        try {
            // Server startup auxiliary class, used to set TCP related parameters
            ServerBootstrap b = new ServerBootstrap();
            // Set as primary and secondary thread model
            b.group(bossGroup, workerGroup)
                    // Set up the server-side NIO communication model
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 5)
                    .childOption(ChannelOption.SO_KEEPALIVE, true) // 2-hour no data activation of heartbeat mechanism
                    // Set up a ChannelPipeline, which is a business responsibility chain composed
                    // of handlers that are concatenated and processed by the thread pool
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        // Add handlers for processing, usually including message encoding and decoding,
                        // business processing, as well as logs, permissions, filtering, etc
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast("http-codec", new HttpServerCodec());
                            ch.pipeline().addLast("aggregator", new HttpObjectAggregator(65535));
                            ch.pipeline().addLast("http-chunked", new ChunkedWriteHandler());
                            ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws")); // Add this line
                            ch.pipeline().addLast("handler", new WebSocketHandler());
                        }
                    });

            // Bind the port, start the select thread, poll and listen for channel events, and once an event is detected, it will be handed over to the thread pool for processing
            channel = b.bind(ip, port).sync().channel();
            System.out.println("WebSocket server started successfully: " + channel + "\n");

            // Wait for the server channel to close
            channel.closeFuture().sync();
        } catch (Exception e) {
            System.err.println("The webSocket server failed to start: " + e.getMessage());
            if (e instanceof java.net.BindException) {
                System.err.println("The port " + port + " is occupied or the IP address " + ip + " is invalid.");
            }
            e.printStackTrace();
        } finally {
            // Exit, release thread pool resources
            if (channel != null) {
                channel.close(); // Close the channel if it was successfully opened
            }
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            System.out.println("Websocket Server closed.");
        }
    }
}