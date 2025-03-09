package com.uni.Websocketserver;

import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.uni.WSBean.WebsocketReq;
import com.uni.WSThread.KeepLiveThread;
import org.apache.commons.codec.binary.Base64;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

public class WebSocketHandler extends ChannelInboundHandlerAdapter {

    private WebSocketServerHandshaker handshaker;
    private static final String SECRET = "123456"; // Authentication key
    private static final String LAPI_REGISTER = "/LAPI/V1.0/System/UpServer/Register";
    private static final String LAPI_KEEPALIVE = "/LAPI/V1.0/System/UpServer/Keepalive";
    private static final String LAPI_UNREGISTER = "/LAPI/V1.0/System/UpServer/Unregister";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof FullHttpRequest) {
                handleHttpRequest(ctx, (FullHttpRequest) msg); // Handle HTTP handshake request
            } else if (msg instanceof WebSocketFrame) {
                handleWebSocketRequest(ctx, (WebSocketFrame) msg); // Handle WebSocket frames
            }
        } finally {
            ReferenceCountUtil.release(msg); // Release the message
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        System.out.println(ctx.channel().remoteAddress() + " connected, IP: " + ctx.channel().remoteAddress());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        System.out.println(ctx.channel().remoteAddress() + " disconnected, IP: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("Client connected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("Client disconnected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.out.println("Exception occurred: " + cause.getMessage());
        ctx.close();
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        String uri = req.uri();
        System.out.println("Received handshake request, URL: " + uri);

        // Validate the handshake request
        if (!req.decoderResult().isSuccess() || (!"websocket".equals(req.headers().get("Upgrade")))) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
            System.out.println("Invalid WebSocket handshake request");
            return;
        }

        // Handle registration requests
        if (uri.contains(LAPI_REGISTER)) {
            handleRegistrationRequest(ctx, req);
            return;
        }

        // Perform WebSocket handshake
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                getWebSocketLocation(req), null, false, 65535 * 100);
        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), req).addListener(future -> {
                if (future.isSuccess()) {
                    System.out.println("WebSocket handshake successful");
                } else {
                    System.out.println("WebSocket handshake failed: " + future.cause().getMessage());
                }
            });
        }
    }

    private void handleRegistrationRequest(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        JSONObject response = new JSONObject();
        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        Map<String, List<String>> params = decoder.parameters();

        // Validate registration parameters
        if (!params.containsKey("Vendor") || !params.containsKey("DeviceType") || !params.containsKey("DeviceCode")
                || !params.containsKey("Algorithm") || !params.containsKey("Nonce") || !params.containsKey("Sign")) {
            response.put("Nonce", getCnonce());
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED,
                    Unpooled.copiedBuffer(response.toJSONString(), CharsetUtil.UTF_8)));
            return;
        }

        // Verify the signature
        String pstr = params.get("Vendor").get(0) + "/" + params.get("DeviceType").get(0) + "/"
                + params.get("DeviceCode").get(0) + "/" + params.get("Algorithm").get(0) + "/"
                + params.get("Nonce").get(0);
        String sign = URLDecoder.decode(params.get("Sign").get(0), StandardCharsets.UTF_8.toString()).replace(" ", "+");

        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(SECRET.getBytes("utf-8"), "HmacSHA256");
        sha256_HMAC.init(secretKey);
        String serverSign = Base64.encodeBase64String(sha256_HMAC.doFinal(pstr.getBytes("utf-8")));

        if (!serverSign.equals(sign)) {
            response.put("Nonce", getCnonce());
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED,
                    Unpooled.copiedBuffer(response.toJSONString(), CharsetUtil.UTF_8)));
            System.out.println("Authentication failed");
            return;
        }

        System.out.println("Authentication successful");
        response.put("Cnonce", params.containsKey("Cnonce") ? params.get("Cnonce").get(0) : "");
        response.put("Resign", serverSign);
        sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(response.toJSONString(), CharsetUtil.UTF_8)));
    }

    private void handleWebSocketRequest(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            System.out.println("Client closed connection: " + ctx.channel().remoteAddress());
            return;
        }

        if (frame instanceof PingWebSocketFrame) {
            ctx.write(new PongWebSocketFrame(frame.content().retain()));
            return;
        }

        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException("Unsupported frame type: " + frame.getClass().getName());
        }

        String text = ((TextWebSocketFrame) frame).text();
        JSONObject json = JSONObject.parseObject(text);
        WebsocketReq request = JSON.toJavaObject(json, WebsocketReq.class);

        if (LAPI_KEEPALIVE.equals(request.getRequestURL())) {
            System.out.println("Received keep-alive request: " + request.getRequestURL());
            KeepLiveThread keepLiveThread = new KeepLiveThread(ctx.channel(), json);
            KeepLiveThreadPoolExecutor.EXECUTOR_SERVICE.execute(keepLiveThread);
        } else if (LAPI_UNREGISTER.equals(request.getRequestURL())) {
            System.out.println("Client unregistered: " + ctx.channel().remoteAddress());
        }
    }

    private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
        if (res.status().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
            HttpUtil.setContentLength(res, res.content().readableBytes());
        }

        ChannelFuture future = ctx.channel().writeAndFlush(res);
        if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String getWebSocketLocation(FullHttpRequest req) {
        return "ws://" + req.headers().get("Host") + "/ws";
    }

    private static String getCnonce() {
        double d = Math.random();
        double d1 = new Date().getTime() / 1000;
        DecimalFormat df = new DecimalFormat("#");
        return df.format(d * d1);
    }
}